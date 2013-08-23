package uk.co.ukmaker.netsim.amqp.node;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import uk.co.ukmaker.netsim.amqp.Routing;
import uk.co.ukmaker.netsim.amqp.messages.broadcast.BroadcastMessage;
import uk.co.ukmaker.netsim.amqp.messages.discovery.EnumeratedMessage;

import com.rabbitmq.client.AMQP.BasicProperties;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.Consumer;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;

/**
 * Listens to messages coming in on the broadcast queue
 * 
 * @author mcintyred
 *
 */
@Service
public class BroadcastListener {
	
	@Autowired 
	Routing routing;
	
	@Autowired
	private ConnectionFactory connectionFactory;
	private Channel broadcastChannel;
	private Channel discoveryChannel;
	
	@Autowired
	private Node node;
	
	public void initialise() throws Exception {
		
		broadcastChannel = connectionFactory.newConnection().createChannel();
		broadcastChannel.queueDeclare(routing.getBroadcastQueueName(node), false, false, false, null);
		broadcastChannel.queueBind(routing.getBroadcastQueueName(node), routing.getBroadcastExchangeName(), "");
		broadcastChannel.basicQos(1);
		
		discoveryChannel = connectionFactory.newConnection().createChannel();
		discoveryChannel.exchangeDeclare(routing.getDiscoveryExchangeName(), "direct");
		discoveryChannel.basicQos(1);
		
		Consumer broadcastCallback = new DefaultConsumer(broadcastChannel) {
			@Override
			public void handleDelivery(String consumerTag, Envelope envelope,
					BasicProperties properties, byte[] body) throws IOException {
				try {
					onBroadcastMessage(properties, body);
				} catch (Exception e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}		
		};

		broadcastChannel.basicConsume(routing.getBroadcastQueueName(node), true, broadcastCallback);
	}
	

	public void onBroadcastMessage(BasicProperties properties, byte[] body) throws Exception {
		
		BroadcastMessage cm = BroadcastMessage.read(properties.getHeaders(), body);
		
		switch(cm.getType()) {
		
		case ENUMERATE:
			enumerate();
			break;

		case CLEAR:
			clear();
			break;
			
		case RESET:
			reset();
			break;
			
		default:
			break;
			
		}
	}
	
	public void enumerate() throws IOException {
		
		EnumeratedMessage m = new EnumeratedMessage(node.getName(), node.getRamSize());
		
		Map<String, Object> headers = new HashMap<String, Object>();
		m.populateHeaders(headers);
		
		BasicProperties props = new BasicProperties.Builder().headers(headers).build();
		
		discoveryChannel.basicPublish(routing.getDiscoveryExchangeName(), "", props, m.getBytes());
	}
	
	public void clear() throws Exception {
		node.clear();
	}
	
	public void reset() throws Exception {
		node.reset();
	}

}
