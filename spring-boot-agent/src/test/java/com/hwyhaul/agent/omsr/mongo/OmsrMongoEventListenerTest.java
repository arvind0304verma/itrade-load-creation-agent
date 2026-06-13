package com.hwyhaul.agent.omsr.mongo;

import com.mongodb.MongoCommandException;
import com.mongodb.ServerAddress;
import org.bson.BsonDocument;
import org.bson.BsonDouble;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OmsrMongoEventListenerTest {

    @Test
    void treatsMongoUnauthorizedCommandAsUnauthorized() {
        assertTrue(OmsrMongoEventListener.isUnauthorized(commandException(13)));
    }

    @Test
    void treatsWrappedMongoUnauthorizedCommandAsUnauthorized() {
        RuntimeException wrapped = new RuntimeException(commandException(13));

        assertTrue(OmsrMongoEventListener.isUnauthorized(wrapped));
    }

    @Test
    void ignoresOtherMongoCommandFailures() {
        assertFalse(OmsrMongoEventListener.isUnauthorized(commandException(26)));
    }

    private MongoCommandException commandException(int code) {
        BsonDocument response = new BsonDocument("ok", new BsonDouble(0.0))
                .append("code", new BsonInt32(code))
                .append("errmsg", new BsonString("test error"));
        return new MongoCommandException(response, new ServerAddress("localhost", 27017));
    }
}
