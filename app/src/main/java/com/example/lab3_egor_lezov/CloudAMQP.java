package com.example.lab3_egor_lezov;

import android.util.Log;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URISyntaxException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.TimeoutException;

public class CloudAMQP {

    String uri = "amqps://hhupcfai:1KqWHDo_4hlOf1jqLr5iMPN_qB0Ty_d4@sparrow.rmq.cloudamqp.com/hhupcfai";
    ConnectionFactory factory = new ConnectionFactory();
    Channel channel;
    Thread subscribeThread;
    Thread publishThread;

    static String userName = "31099";
    float colurMarker;

    static final String secretKey = "7vr36cxv8ttc36873wr6x8ifcb";

    void setupConnection() {
        try {
            colurMarker = getHueForUser(userName);
            factory.setAutomaticRecoveryEnabled(false);
            factory.setUri(uri);

            new Thread(() -> {
                try {
                    Connection connection = factory.newConnection();
                    channel = connection.createChannel();
                    channel.confirmSelect();

                    subscribeToQueue();
                } catch (IOException | TimeoutException e) {
                    throw new RuntimeException(e);
                }
            }).start();
        } catch (URISyntaxException | NoSuchAlgorithmException | KeyManagementException e) {
            e.printStackTrace();
        }
    }

    public static float getHueForUser(String userName) {
        int hashCode = userName.hashCode();
        float hue = Math.abs(hashCode % 360);
        return hue;
    }

    public void publishToExchange(JSONObject payload) {
        (publishThread = new Thread(() -> {
            try {
                channel.basicPublish("FanoutExchange", "",
                        new AMQP.BasicProperties.Builder()
                                .expiration("1000")
                                .build(),
                        AES.encrypt(payload.toString(), secretKey).getBytes());
                channel.waitForConfirmsOrDie();
            } catch (Exception e) {
                Log.d("", "Connection broken: " + e.getClass().getName());
            }
        })).start();
    }

    void subscribeToQueue() {
        (subscribeThread = new Thread(() -> {
            DeliverCallback deliverCallback = (consumerTag, delivery) -> {
                try {
                    String decrypted = AES.decrypt(new String(delivery.getBody(), "UTF-8"), secretKey);
                    JSONObject json = new JSONObject(decrypted);
                    MapsActivity.consumeUserLocations(json);
                } catch (JSONException e) {
                    throw new RuntimeException(e);
                }
            };

            try {
                AMQP.Queue.DeclareOk declareOk =  channel.queueDeclare(userName, false, false,true,null);
                String queueName = declareOk.getQueue();

                channel.queueBind(queueName, "FanoutExchange", "");
                channel.basicConsume(queueName, true, deliverCallback, consumerTag -> {});
            } catch(IOException e) {
                throw new RuntimeException(e);
            }
        })).start();
    }
}