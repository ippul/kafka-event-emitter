package org.ippul.example.pam.emitter;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.jbpm.persistence.api.integration.EventCollection;
import org.jbpm.persistence.api.integration.EventEmitter;
import org.jbpm.persistence.api.integration.InstanceView;
import org.jbpm.persistence.api.integration.base.BaseEventCollection;
import org.jbpm.persistence.api.integration.model.TaskInstanceView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class KafkaEventEmitter implements EventEmitter {

    private static final Logger logger = LoggerFactory.getLogger(KafkaEventEmitter.class);

    private static String dateFormatStr = System.getProperty("org.kie.server.json.date_format", "yyyy-MM-dd'T'hh:mm:ss.SSSZ");

    private static final String BOOTSTRAP_SERVER = System.getProperty("org.ippul.example.pam.emitter.bootstrap.servers");
    private static final String TOPIC_NAME = System.getProperty("org.ippul.example.pam.emitter.topic.name");

    private final ObjectMapper mapper = new ObjectMapper();

    private KafkaProducer<String, String> producer;

    private ExecutorService executor = Executors.newCachedThreadPool();

    public KafkaEventEmitter(){
        mapper.setDateFormat(new SimpleDateFormat(dateFormatStr));
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        mapper.configure(MapperFeature.PROPAGATE_TRANSIENT_MARKER, true);
        mapper.setVisibilityChecker(
                mapper.getSerializationConfig().
                        getDefaultVisibilityChecker().
                        withFieldVisibility(JsonAutoDetect.Visibility.ANY).
                        withGetterVisibility(JsonAutoDetect.Visibility.NONE));

        Properties props = new Properties();
        props.put("bootstrap.servers", BOOTSTRAP_SERVER);
        props.put("acks", "all");
        props.put("retries", 0);
        props.put("batch.size", 16384);
        props.put("linger.ms", 1);
        props.put("buffer.memory", 33554432);
        props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        this.producer = new KafkaProducer<String, String>(props);
    }

    @Override
    public void deliver(Collection<InstanceView<?>> collection) {

    }

    @Override
    public void apply(Collection<InstanceView<?>> collection) {
        if (!collection.isEmpty()) {
            executor.execute(() -> {
                StringBuilder content = new StringBuilder();
                for (InstanceView<?> view : collection) {
                    if (view instanceof TaskInstanceView) {
                        String id = ((TaskInstanceView) view).getCompositeId();
                        try {
                            ProducerRecord record = new ProducerRecord<String, String>(TOPIC_NAME, id, mapper.writeValueAsString(view));
                            producer.send(record, new ResultLoggerCallback());
                        } catch (JsonProcessingException e) {
                            e.printStackTrace();
                        }
                    }
                }
            });
        }
    }

    @Override
    public void drop(Collection<InstanceView<?>> collection) {

    }

    @Override
    public EventCollection newCollection() {
        return new BaseEventCollection();
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            producer.close();
        } catch (Exception e) {
            logger.error("Exception occurred while stopping the producer", e);
        }
    }

    private class ResultLoggerCallback implements Callback {
        @Override
        public void onCompletion(RecordMetadata recordMetadata, Exception e) {
            if (e != null) {
                logger.error("Error while producing message to topic : {}", recordMetadata.topic(), e);
            } else
                logger.debug("sent message to topic:{} partition:{}  offset:{}", recordMetadata.topic(), recordMetadata.partition(), recordMetadata.offset());
        }
    }
}
