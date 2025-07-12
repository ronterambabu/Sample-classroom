package com.zn.config;

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
	  private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
	  private final Map<String, Set<WebSocketSession>> rooms = new ConcurrentHashMap<>();


  @Override
  public void afterConnectionEstablished(WebSocketSession session) {
	String sessionId = session.getId();
	sessions.put(sessionId, session);
  }


  @Override
  public void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
	  ObjectMapper mapper = new ObjectMapper();
	  JsonNode json = mapper.readTree(message.getPayload());
	  String type = json.get("type").asText();

	  if ("join".equals(type)) {
		  String roomId = json.get("room").asText();
		  rooms.putIfAbsent(roomId, ConcurrentHashMap.newKeySet());
		  rooms.get(roomId).add(session);

		  // Notify all other peers in the room about the new peer
		  for (WebSocketSession s : rooms.get(roomId)) {
			  if (!s.equals(session)) {
				  s.sendMessage(new TextMessage(
					  mapper.writeValueAsString(Map.of(
						  "type", "new-peer",
						  "id", session.getId()
					  ))
				  ));
				  // Also notify the new peer about existing peers
				  session.sendMessage(new TextMessage(
					  mapper.writeValueAsString(Map.of(
						  "type", "new-peer",
						  "id", s.getId()
					  ))
				  ));
			  }
		  }
	  }

	  else if ("signal".equals(type)) {
		  String toId = json.get("to").asText();
		  // Only send to the peer in the same room
		  for (Set<WebSocketSession> participants : rooms.values()) {
			  for (WebSocketSession s : participants) {
				  if (s.getId().equals(toId)) {
					  s.sendMessage(new TextMessage(message.getPayload()));
					  break;
				  }
			  }
		  }
	  }
  }
  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
	sessions.remove(session.getId());
	// Remove from all rooms
	for (Set<WebSocketSession> participants : rooms.values()) {
	  participants.remove(session);
	}
  }
	// In SignalingHandler.java


	 

	}
