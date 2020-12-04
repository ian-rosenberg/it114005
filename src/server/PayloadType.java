package server;

public enum PayloadType {
	CONNECT, 
	DISCONNECT, 
	MESSAGE, 
	CLEAR_PLAYERS, 
	SYNC_DIRECTION, 
	SYNC_POSITION,
	SYNC_PLAYER_DIRECTION,
	SHOOT, 
	CREATE_ROOM, 
	JOIN_ROOM,
	GET_ROOMS, 
	SYNC_DIMENSIONS, 
	ASSIGN_ID, 
	SET_TEAM_INFO, 
	READY, 
	GAME_STARTED, 
	SET_ACTIVITY, 
	GAME_STATE,
	TIMER,
}