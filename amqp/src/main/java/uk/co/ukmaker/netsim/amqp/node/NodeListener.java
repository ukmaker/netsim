package uk.co.ukmaker.netsim.amqp.node;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import uk.co.ukmaker.netsim.ScheduledValue;
import uk.co.ukmaker.netsim.amqp.Routing;
import uk.co.ukmaker.netsim.amqp.messages.Message;
import uk.co.ukmaker.netsim.amqp.messages.netlist.ScheduleNetValueMessage;
import uk.co.ukmaker.netsim.amqp.messages.node.InitialiseModelsMessage;
import uk.co.ukmaker.netsim.amqp.messages.node.InstallModelMessage;
import uk.co.ukmaker.netsim.amqp.messages.node.PropagateInputsMessage;
import uk.co.ukmaker.netsim.amqp.messages.node.PropagateOutputsMessage;
import uk.co.ukmaker.netsim.amqp.messages.node.UpdateModelsMessage;
import uk.co.ukmaker.netsim.amqp.messages.nodereply.PropagatedNetDriversMessage;
import uk.co.ukmaker.netsim.amqp.messages.nodereply.SimpleAckMessage;
import uk.co.ukmaker.netsim.amqp.messages.nodereply.UpdateEventQueueMessage;
import uk.co.ukmaker.netsim.models.Model;
import uk.co.ukmaker.netsim.simulation.NetEventPropagator;

import com.rabbitmq.client.AMQP.BasicProperties;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.Consumer;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;

/**
 * Handles messages on the node queue
 * @author mcintyred
 *
 */
@Service
public class NodeListener implements NetEventPropagator {
	
	@Autowired 
	Routing routing;
	
	@Autowired
	private ConnectionFactory connectionFactory;
	private Channel nodeChannel;
	private Channel netsChannel;
	
	@Autowired
	private Node node;
	
	private Consumer nodeCallback;
	private String nodeQueueName;
	private String netsExchangeName;

	public void initialise() throws Exception {
		
		nodeQueueName = routing.getNodeQueueName(node);
		
		nodeChannel = connectionFactory.newConnection().createChannel();
		nodeChannel.exchangeDeclare(routing.getNodesExchangeName(), "direct");
		nodeChannel.queueDeclare(nodeQueueName, false, true, true, null);
		nodeChannel.queueBind(nodeQueueName, routing.getNodesExchangeName(), routing.getNodeRoutingKey(node), null);
		nodeChannel.basicQos(1);
		
		nodeCallback = new DefaultConsumer(nodeChannel) {
			@Override
			public void handleDelivery(String consumerTag, Envelope envelope,
					BasicProperties properties, byte[] body) throws IOException {
				
				String message = new String(body);
				System.out.println("Processing node message: "+message);

				try {
					Message reply = onNodeMessage(properties, body);
					if(reply != null) {
						
						Map<String, Object> replyHeaders = new HashMap<String, Object>();
						reply.populateHeaders(replyHeaders);
						
						
						BasicProperties replyProps = new BasicProperties.Builder()
						.headers(replyHeaders)
						.build();
						
						nodeChannel.basicPublish("", properties.getReplyTo(), replyProps, reply.getBytes());
					}
				} catch (Exception e) {
					throw new IOException("Error handling message", e);
				}
			}		
		};

		nodeChannel.basicConsume(nodeQueueName, true, nodeCallback);
		
		netsChannel = connectionFactory.newConnection().createChannel();
		netsExchangeName = routing.getNetsExchangeName();
	}
	
	public Message onNodeMessage(BasicProperties properties, byte[] body) throws Exception {
	
		String type = properties.getHeaders().get(Message.TYPE_HEADER).toString();
		
		if(InitialiseModelsMessage.TYPE.equals(type)) {
			return initialiseModels(InitialiseModelsMessage.read(properties.getHeaders(), body));
		}
		
		if(PropagateInputsMessage.TYPE.equals(type)) {
			return propagateInputs(PropagateInputsMessage.read(properties.getHeaders(), body));
		}
		
		if(PropagateOutputsMessage.TYPE.equals(type)) {
			return propagateOutputs(PropagateOutputsMessage.read(properties.getHeaders(), body));
		}
		
		if(UpdateModelsMessage.TYPE.equals(type)) {
			return updateModels(UpdateModelsMessage.read(properties.getHeaders(), body));
		}
		
		if(InstallModelMessage.TYPE.equals(type)) {
			return installModel(InstallModelMessage.read(properties.getHeaders(), body));
		}
		
		throw new Exception("Unknown message type "+type+" received by NodeListener");
	}
	
	public Message initialiseModels(InitialiseModelsMessage m) throws Exception {
		 Map<String, Set<Long>>  netEvents = node.getNetlistDriver().initialiseModels();
		return new UpdateEventQueueMessage(netEvents);
	}
	
	public Message propagateInputs(PropagateInputsMessage m) throws Exception {
		node.getNetlistDriver().propagateInputs(m.getMoment(), m.getNetDrivers());
		return new SimpleAckMessage();
	}
	
	public Message propagateOutputs(PropagateOutputsMessage m) throws Exception {
		Map<String, Integer> netDrivers = node.getNetlistDriver().propagateOutputs(m.getMoment(), m.getNetIds(), this);
		return new PropagatedNetDriversMessage(netDrivers);
	}
	
	public Message updateModels(UpdateModelsMessage m) throws Exception {
		Map<String, Set<Long>> nextEvents = node.getNetlistDriver().updateModels(m.getMoment());
		return new UpdateEventQueueMessage(nextEvents);
	}

	public Message installModel(InstallModelMessage installModelMessage) throws InstantiationException, IllegalAccessException, ClassNotFoundException {
		
		Model model = (Model)Class.forName(installModelMessage.getClassName()).newInstance();
		
		model.setUnitId(installModelMessage.getUnitId());
		model.setName(installModelMessage.getName());
		
		node.addModel(model);
		
		for(String pinName : installModelMessage.getPinToNetMap().keySet()) {
			String netId = installModelMessage.getPinToNetMap().get(pinName);
			node.connectPin(model, netId, pinName);
		}
		return new SimpleAckMessage();
	}

	@Override
	public void propagateOutput(String netId, ScheduledValue value) throws Exception {
		
		ScheduleNetValueMessage m = new ScheduleNetValueMessage(netId, value);
		
		Map<String, Object> headers = new HashMap<String, Object>();
		
		m.populateHeaders(headers);
		
		BasicProperties props = new BasicProperties.Builder()
		.headers(headers)
		.build();

		netsChannel.basicPublish(netsExchangeName, netId, props, m.getBytes());	
		
	}
}
