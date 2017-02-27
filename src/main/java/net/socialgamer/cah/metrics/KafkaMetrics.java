/**
 * Copyright (c) 2017, Andy Janata
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted
 * provided that the following conditions are met:
 *
 * * Redistributions of source code must retain the above copyright notice, this list of conditions
 *   and the following disclaimer.
 * * Redistributions in binary form must reproduce the above copyright notice, this list of
 *   conditions and the following disclaimer in the documentation and/or other materials provided
 *   with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY
 * WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package net.socialgamer.cah.metrics;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.annotation.Nullable;

import net.socialgamer.cah.data.BlackCard;
import net.socialgamer.cah.data.CardSet;
import net.socialgamer.cah.data.WhiteCard;
import net.socialgamer.cah.db.PyxBlackCard;
import net.socialgamer.cah.db.PyxCardSet;
import net.socialgamer.cah.db.PyxWhiteCard;

import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.log4j.Logger;
import org.json.simple.JSONValue;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.maxmind.geoip2.model.CityResponse;


/**
 * Metrics implementation that sends all data to an Apache Kafka topic.
 *
 * @author Andy Janata (ajanata@socialgamer.net)
 */
@Singleton
public class KafkaMetrics implements Metrics {

  private static final String metricsVersion = "0.1";
  private static final Logger LOG = Logger.getLogger(KafkaMetrics.class);

  private final ProducerCallback callback = new ProducerCallback();
  private final String build;
  private final String hosts;
  private final String topic;
  private volatile Producer<String, String> producer;
  private final Properties producerProps;
  private final Lock makeProducerLock = new ReentrantLock();

  @Inject
  public KafkaMetrics(final Properties properties) {
    build = properties.getProperty("pyx.build");
    hosts = properties.getProperty("kafka.host");
    topic = properties.getProperty("kafka.topic");
    LOG.info("Sending metrics to Kafka topic " + topic);
    producerProps = getProducerProps();
    tryEnsureProducer();
  }

  private Properties getProducerProps() {
    final Properties props = new Properties();
    props.put("bootstrap.servers", hosts);
    props.put("key.serializer", StringSerializer.class.getName());
    props.put("value.serializer", StringSerializer.class.getName());
    props.put("acks", "0");
    props.put("compression.type", "gzip");
    props.put("retries", 1);
    props.put("client.id", "pyx-" + build);
    props.put("max.block.ms", TimeUnit.SECONDS.toMillis(5));
    // TODO TLS, authentication
    return props;
  }

  /**
   * Helper method to log at TRACE level while only taking string format penalties if such logging
   * is enabled. Includes the method name as well.
   * @param format Format string to log
   * @param params Parameters for format string
   */
  private void trace(final String format, final Object... params) {
    if (LOG.isTraceEnabled()) {
      final StackTraceElement[] stack = Thread.currentThread().getStackTrace();
      final String message = String.format(format, params);
      // skip getStackTrace and this method
      LOG.trace(String.format("%s: %s", stack[2].getMethodName(), message));
    }
  }

  /**
   * Attempt to create a producer. {@link #producer} must still be checked against null after
   * calling this method.
   */
  private void tryEnsureProducer() {
    if (null != producer) {
      return;
    }

    if (makeProducerLock.tryLock()) {
      try {
        LOG.info("Attempting to create producer.");
        final Producer<String, String> newProducer = new KafkaProducer<>(producerProps);
        final List<PartitionInfo> info = newProducer.partitionsFor(topic);
        LOG.info(String.format("Topic %s has %d partitions", topic, info.size()));
        final Producer<String, String> oldProducer = producer;
        producer = newProducer;
        if (null != oldProducer) {
          LOG.info("Old producer closed.");
          oldProducer.close();
        }
        LOG.info("Producer created.");
      } catch (final Exception e) {
        LOG.error("Unable to retrieve partition info for topic " + topic, e);
      } finally {
        makeProducerLock.unlock();
      }
    } else {
      LOG.warn("Another thread is creating a producer.");
    }
  }

  private void send(final Map<String, Object> map) {
    send(JSONValue.toJSONString(map));
  }

  private void send(final String json) {
    trace("%s", json);
    tryEnsureProducer();
    if (null != producer) {
      final ProducerRecord<String, String> record = new ProducerRecord<>(topic, null, json);
      producer.send(record, callback);
    } else {
      LOG.warn("Dropping event " + json);
    }
  }

  private class ProducerCallback implements Callback {
    @Override
    public void onCompletion(final RecordMetadata metadata, final Exception exception) {
      if (null != exception) {
        LOG.error("Unable to send event to Kafka", exception);
        final Producer<String, String> oldProducer = producer;
        producer = null;
        if (null != oldProducer) {
          LOG.info("Closing producer after exception.");
          oldProducer.close();
        }
      }
    }
  }

  @Override
  public void shutdown() {
    trace("");
    if (null != producer) {
      producer.close();
    }
  }

  private Map<String, Object> getEventMap(final String type, final Map<String, Object> data) {
    final Map<String, Object> ret = new HashMap<>();
    ret.put("timestamp", System.currentTimeMillis());
    ret.put("build", build);
    ret.put("type", type);
    ret.put("data", data);
    ret.put("version", metricsVersion);
    return ret;
  }

  @Override
  public void serverStart(final String startupId) {
    trace("%s", startupId);
    final Map<String, Object> data = new HashMap<>();
    data.put("startupId", startupId);
    send(getEventMap("serverStart", data));
  }

  @Override
  public void userConnect(final String persistentId, final String sessionId,
      @Nullable final CityResponse geoIp, final String agentName, final String agentType,
      final String agentOs, final String agentLanguage) {
    trace("%s, %s, %s, %s, %s, %s, %s", persistentId, sessionId, geoIp, agentName, agentType,
        agentOs, agentLanguage);

    final Map<String, Object> data = new HashMap<>();
    data.put("persistentId", persistentId);
    data.put("sessionId", sessionId);

    final Map<String, Object> browser = new HashMap<>();
    browser.put("name", agentName);
    browser.put("type", agentType);
    browser.put("os", agentOs);
    browser.put("language", agentLanguage);
    data.put("browser", browser);

    final Map<String, Object> geo = new HashMap<>();
    if (null != geoIp) {
      // it appears these will never be null and will return null/blank data, but let's be sure
      if (null != geoIp.getCity()) {
        geo.put("city", geoIp.getCity().getName());
      }
      if (null != geoIp.getCountry()) {
        geo.put("country", geoIp.getCountry().getIsoCode());
      }
      final List<String> subdivCodes = new ArrayList<>(2);
      geoIp.getSubdivisions().forEach(subdiv -> subdivCodes.add(subdiv.getIsoCode()));
      if (!subdivCodes.isEmpty()) {
        geo.put("subdivisions", subdivCodes);
      }
      if (null != geoIp.getRepresentedCountry()) {
        geo.put("representedCountry", geoIp.getRepresentedCountry().getIsoCode());
      }
      if (null != geoIp.getPostal()) {
        geo.put("postal", geoIp.getPostal().getCode());
      }
    }
    data.put("geo", geo);

    send(getEventMap("userConnect", data));
  }

  @Override
  public void userDisconnect(final String sessionId) {
    trace("%s", sessionId);

    final Map<String, Object> data = new HashMap<>();
    data.put("sessionId", sessionId);
    send(getEventMap("userDisconnect", data));
  }

  @Override
  public void gameStart(final String gameId, final Collection<CardSet> decks, final int blanks,
      final int maxPlayers, final int scoreGoal, final boolean hasPassword) {
    trace("%s, %s, %d, %d, %d, %s", gameId, decks.toArray(), blanks, maxPlayers, scoreGoal,
        hasPassword);

    final Map<String, Object> data = new HashMap<>();
    data.put("gameId", gameId);
    data.put("blankCardsInDeck", blanks);
    data.put("maxPlayers", maxPlayers);
    data.put("scoreGoal", scoreGoal);
    data.put("hasPassword", hasPassword);

    final List<Map<String, Object>> deckInfos = new ArrayList<>(decks.size());
    for (final CardSet deck : decks) {
      final Map<String, Object> deckInfo = new HashMap<>();
      // if we ever have more than cardcast for custom cards, this needs updated to indicate which
      // custom deck source, but will still be correct for this specific flag
      deckInfo.put("isCustom", !(deck instanceof PyxCardSet));
      deckInfo.put("id", deck.getId());
      // TODO(?) don't include these data for non-custom decks?
      deckInfo.put("name", deck.getName());
      deckInfo.put("whiteCount", deck.getWhiteCards().size());
      deckInfo.put("blackCount", deck.getBlackCards().size());
      deckInfos.add(deckInfo);
    }
    data.put("decks", deckInfos);

    send(getEventMap("gameStart", data));
  }

  @Override
  public void roundComplete(final String gameId, final String roundId, final String judgeSessionId,
      final String winnerSessionId, final BlackCard blackCard,
      final Map<String, List<WhiteCard>> cards) {
    trace("%s, %s, %s, %s, %s, %s", gameId, roundId, judgeSessionId, winnerSessionId, blackCard,
        cards);

    final Map<String, Object> data = new HashMap<>();
    data.put("gameId", gameId);
    data.put("roundId", roundId);
    data.put("judgeSessionId", judgeSessionId);
    data.put("winnerSessionId", winnerSessionId);

    // <player id, cards[<id key, value>]>
    final Map<String, List<Map<String, Object>>> allCardMap = new HashMap<>();
    for (final Entry<String, List<WhiteCard>> cardsByUser : cards.entrySet()) {
      final List<Map<String, Object>> userCards = new ArrayList<>(cardsByUser.getValue().size());
      for (final WhiteCard card : cardsByUser.getValue()) {
        final Map<String, Object> cardInfo = new HashMap<>();
        // same re: more custom deck sources
        cardInfo.put("isCustom", !(card instanceof PyxWhiteCard));
        cardInfo.put("isWriteIn", card.isWriteIn());
        // negative IDs would be custom: either blank or cardcast. they are not stable.
        cardInfo.put("id", card.getId());
        cardInfo.put("text", card.getText());
        userCards.add(cardInfo);
      }
      allCardMap.put(cardsByUser.getKey(), userCards);
    }
    data.put("cardsByUserId", allCardMap);

    final Map<String, Object> blackCardData = new HashMap<>();
    // same re: more custom deck sources
    blackCardData.put("isCustom", !(blackCard instanceof PyxBlackCard));
    // negative IDs would be custom: either blank or cardcast. they are not stable.
    blackCardData.put("id", blackCard.getId());
    blackCardData.put("text", blackCard.getText());
    blackCardData.put("draw", blackCard.getDraw());
    blackCardData.put("pick", blackCard.getPick());
    data.put("blackCard", blackCardData);

    send(getEventMap("roundComplete", data));
  }
}
