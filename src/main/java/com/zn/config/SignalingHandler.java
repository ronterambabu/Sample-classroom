package com.zn.config;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class SignalingHandler extends TextWebSocketHandler {
	  private final Map<String, Set<WebSocketSession>> rooms = new ConcurrentHashMap<>();


  @Override
  public void afterConnectionEstablished(WebSocketSession session) {
	System.out.println("WebSocket connection established: " + session.getId());
  }


  @Override
  public void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
	  System.out.println("Received message from " + session.getId() + ": " + message.getPayload());
	  try {
		  ObjectMapper mapper = new ObjectMapper();
		  JsonNode json = mapper.readTree(message.getPayload());
		  
		  // Check if type field exists
		  JsonNode typeNode = json.get("type");
		  if (typeNode == null) {
			  System.err.println("Received message without 'type' field: " + message.getPayload());
			  return;
		  }
		  
		  String type = typeNode.asText();

		  if ("join".equals(type)) {
			  JsonNode roomNode = json.get("room");
			  if (roomNode == null) {
				  System.err.println("Received 'join' message without 'room' field: " + message.getPayload());
				  return;
			  }
			  
			  String roomId = roomNode.asText();
			  System.out.println("Session " + session.getId() + " joining room: " + roomId);
			  
			  rooms.putIfAbsent(roomId, ConcurrentHashMap.newKeySet());
			  
			  // Get existing peers in the room before adding the new session
			  Set<WebSocketSession> existingPeers = rooms.get(roomId);
			  System.out.println("Existing peers in room " + roomId + ": " + existingPeers.size());
			  
			  // Send existing peers to the new session
			  if (!existingPeers.isEmpty()) {
				  String[] peerIds = existingPeers.stream()
					  .map(WebSocketSession::getId)
					  .toArray(String[]::new);
				  
				  String existingPeerMessage = mapper.writeValueAsString(Map.of(
					  "type", "existing-peer",
					  "peers", peerIds
				  ));
				  System.out.println("Sending existing-peer message to " + session.getId() + ": " + existingPeerMessage);
				  try {
					  session.sendMessage(new TextMessage(existingPeerMessage));
				  } catch (Exception e) {
					  System.err.println("Error sending existing-peer message: " + e.getMessage());
				  }
			  } else {
				  // Send an empty existing-peer message to acknowledge the join
				  String emptyPeerMessage = mapper.writeValueAsString(Map.of(
					  "type", "existing-peer",
					  "peers", new String[0]
				  ));
				  System.out.println("Sending empty existing-peer message to " + session.getId() + ": " + emptyPeerMessage);
				  try {
					  session.sendMessage(new TextMessage(emptyPeerMessage));
				  } catch (Exception e) {
					  System.err.println("Error sending empty existing-peer message: " + e.getMessage());
				  }
			  }
			  
			  // Add the new session to the room
			  rooms.get(roomId).add(session);
			  System.out.println("Added session " + session.getId() + " to room " + roomId + ". Room now has " + rooms.get(roomId).size() + " participants");

			  // Notify all other peers in the room about the new peer
			  for (WebSocketSession s : existingPeers) {
				  if (!s.equals(session)) {
					  String newPeerMessage = mapper.writeValueAsString(Map.of(
						  "type", "new-peer",
						  "id", session.getId()
					  ));
					  System.out.println("Notifying existing peer " + s.getId() + " about new peer: " + newPeerMessage);
					  try {
						  s.sendMessage(new TextMessage(newPeerMessage));
					  } catch (Exception e) {
						  System.err.println("Error notifying existing peer: " + e.getMessage());
					  }
				  }
			  }
		  }

		  else if ("signal".equals(type)) {
			  JsonNode toNode = json.get("to");
			  JsonNode dataNode = json.get("data");
			  if (toNode == null || dataNode == null) {
				  System.err.println("Received 'signal' message without 'to' or 'data' field: " + message.getPayload());
				  return;
			  }
			  
			  String toId = toNode.asText();
			  
			  // Create a proper signal message with 'from' field
			  Map<String, Object> signalMessage = Map.of(
				  "type", "signal",
				  "from", session.getId(),
				  "data", dataNode
			  );
			  
			  // Only send to the peer in the same room
			  for (Set<WebSocketSession> participants : rooms.values()) {
				  for (WebSocketSession s : participants) {
					  if (s.getId().equals(toId)) {
						  s.sendMessage(new TextMessage(mapper.writeValueAsString(signalMessage)));
						  return; // Exit early once message is sent
					  }
				  }
			  }
		  }
	  } catch (IOException e) {
		  System.err.println("Error parsing JSON message: " + e.getMessage());
	  } catch (IllegalArgumentException e) {
		  System.err.println("Invalid message format: " + e.getMessage());
	  } catch (Exception e) {
		  System.err.println("Unexpected error handling message: " + e.getMessage());
	  }
  }
  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
	System.out.println("WebSocket connection closed: " + session.getId() + ", status: " + status);
	
	// Find which rooms this session was in and notify other participants
	ObjectMapper mapper = new ObjectMapper();
	for (Map.Entry<String, Set<WebSocketSession>> roomEntry : rooms.entrySet()) {
		Set<WebSocketSession> participants = roomEntry.getValue();
		if (participants.remove(session)) {
			System.out.println("Removed session " + session.getId() + " from room " + roomEntry.getKey());
			
			// Notify remaining participants that this peer left
			for (WebSocketSession remainingSession : participants) {
				try {
					String peerLeftMessage = mapper.writeValueAsString(Map.of(
						"type", "peer-left",
						"id", session.getId()
					));
					System.out.println("Notifying " + remainingSession.getId() + " that peer " + session.getId() + " left");
					remainingSession.sendMessage(new TextMessage(peerLeftMessage));
				} catch (Exception e) {
					System.err.println("Error notifying peer about disconnection: " + e.getMessage());
				}
			}
		}
	}
	
	// Clean up empty rooms
	rooms.entrySet().removeIf(entry -> entry.getValue().isEmpty());
	System.out.println("Remaining rooms: " + rooms.size());
  }
}

