package server;

import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Point;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Timer;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import client.Player;
import core.BaseGamePanel;
import core.Projectile;

public class Room extends BaseGamePanel implements AutoCloseable {
	private static SocketServer server;// used to refer to accessible server functions
	private String name;
	private int roomId = -1;
	private final static long MINUTE_NANO = TimeUnit.MINUTES.toNanos(1);
	private final static long ROUND_TIME = TimeUnit.MINUTES.toNanos(5);// Round time is 5 min in nanoseconds
	private final static Logger log = Logger.getLogger(Room.class.getName());
	private GameState state = GameState.LOBBY;

	private final static int TEAM_A = 1;
	private final static int TEAM_B = 2;
	private final static int BULLET_RADIUS = 15;
	private final int MAX_HP = 3;

	private int teamAScore = 0;
	private int teamBScore = 0;
	
	private int teamAPlayers = 0;
	private int teamBPlayers = 0;
	
	// Commands
	private final static String COMMAND_TRIGGER = "/";
	private final static String CREATE_ROOM = "createroom";
	private final static String JOIN_ROOM = "joinroom";
	private final static String READY = "ready";
	private List<ClientPlayer> clients = new ArrayList<ClientPlayer>();
	private List<Projectile> projectiles = new ArrayList<Projectile>();
	private static Dimension gameAreaSize = new Dimension(1280, 720);

	private long timeLeft = ROUND_TIME;
	private int minutesLeft = 5;
	private long currentNS = 0;
	private long prevNS = currentNS;

	public Room(String name, boolean delayStart, int id) {
		super(delayStart);
		this.name = name;
		isServer = true;
		roomId = id;
	}

	public Room(String name, int id) {
		this.name = name;
		// set this for BaseGamePanel to NOT draw since it's server-side
		isServer = true;
		roomId = id;
	}

	public static void setServer(SocketServer server) {
		Room.server = server;
	}

	@Override
	public String getName() {
		return name;
	}

	public int getRoomId() {
		return roomId;
	}

	/*
	 * private void teamAssign(ClientPlayer clientPlayer) { int playerId =
	 * clientPlayer.player.getId();
	 * 
	 * if (playerId % 2 == 0) { clientPlayer.player.setTeam(TEAM_A);
	 * clientPlayer.client.sendTeamInfo(TEAM_A, playerId); } else {
	 * 
	 * clientPlayer.player.setTeam(TEAM_B); clientPlayer.client.sendTeamInfo(TEAM_B,
	 * playerId); }
	 * 
	 * clientPlayer.client.sendBoundary(gameAreaSize); }
	 */

	private ClientPlayer getClientPlayer(ServerThread client) {
		Iterator<ClientPlayer> iter = clients.iterator();
		while (iter.hasNext()) {
			ClientPlayer cp = iter.next();
			if (cp.client == client) {
				return cp;
			}
		}
		return null;
	}

	private static Point getStartPosition(int id) {
		Point startPos = new Point();
		if(id % 2 == 0) {
			startPos.x = gameAreaSize.width - 100;
		}
		else {
			startPos.x = 100;
		}
		
		startPos.y = (int) (Math.random() * gameAreaSize.height);
		return startPos;
	}

	protected void createRoom(String room, ServerThread client) {
		if (server.createNewRoom(room)) {
			sendMessage(client, "Created a new room");
			joinRoom(room, client);
		}
	}

	private void syncTeamSelfAndBroadcast(ServerThread client, Player p) {
		// client.sendTeamInfo(p.getName(), p.getTeam());
		for (ClientPlayer cp : clients) {
			if (cp != null && cp.client != null) {
				cp.client.sendTeamInfo(p.getName(), p.getTeam());
			}
		}
	}

	protected synchronized void addClient(ServerThread client) {
		System.out.println("Client joining room " + roomId);
		client.setCurrentRoom(this);
		boolean exists = false;
		Player newPlayer = null;
		// since we updated to a different List type, we'll need to loop through to find
		// the client to check against
		Iterator<ClientPlayer> iter = clients.iterator();
		while (iter.hasNext()) {
			ClientPlayer c = iter.next();
			if (c.client == client) {
				exists = true;
				if (c.player == null) {
					log.log(Level.WARNING, "Client " + client.getClientName() + " player was null, creating");
					newPlayer = new Player();
					c.player = newPlayer;
				}
				break;
			}
		}

		if (exists) {
			log.log(Level.INFO, "Attempting to add a client that already exists");
		}

		if (newPlayer == null) {
			newPlayer = new Player();
		}
		newPlayer.setName(client.getClientName());
		newPlayer.setId(clients.size());
		newPlayer.setHP(MAX_HP);
		ClientPlayer cp = new ClientPlayer(client, newPlayer);
		clients.add(cp);
		if (roomId < 0) {
			return;
		}
		// next few lines sync name and id
		// this one we need to send separately since we're not on the client list yet
		// client.sendConnectionStatus(client.getClientName(), true, "joined the room "
		// + getName(), newPlayer.getId());
		client.sendClearList();
		sendConnectionStatus(client, true, "joined the room " + getName(), newPlayer.getId());
		// this happens before we're added to the clients list
		newPlayer.setTeam(newPlayer.getId() % 2 == 0 ? TEAM_A : TEAM_B);
		syncTeamSelfAndBroadcast(client, newPlayer);

		client.sendBoundary(gameAreaSize);
		Point startPos = Room.getStartPosition(newPlayer.getId());
		newPlayer.setPosition(startPos);
		client.sendPosition(newPlayer.getId(), startPos);
		sendPositionSync(newPlayer.getId(), startPos);
		updateClientList(client);
		updatePlayers(client);

		// ClientPlayer cp = new ClientPlayer(client, newPlayer);
		// clients.add(cp);
		broadcastSetPlayersInactive();

	}

	/***
	 * Syncs the existing players in the room with our newly connected player
	 * 
	 * @param client
	 */
	private synchronized void updatePlayers(ServerThread client) {
		// when we connect, send all existing clients current position and direction so
		// we can locally show this on our client
		Iterator<ClientPlayer> iter = clients.iterator();
		while (iter.hasNext()) {
			ClientPlayer c = iter.next();
			if (c.client != client) {
				client.sendConnectionStatus(c.player.getName(), true, null, c.player.getId());
				boolean messageSent = client.sendDirection(c.player.getId(), c.player.getDirection());
				if (messageSent) {
					if (client.sendPosition(c.player.getId(), c.player.getPosition())) {
						if (client.sendTeamInfo(c.player.getName(), c.player.getTeam())) {
							// everything should be synced
						}
					}
				}
			}
		}
	}

	/**
	 * Syncs the existing clients in the room with our newly connected client
	 * 
	 * @param client
	 */
	private synchronized void updateClientList(ServerThread client) {
		Iterator<ClientPlayer> iter = clients.iterator();
		while (iter.hasNext()) {
			ClientPlayer c = iter.next();
			if (c.client != client) {
				client.sendConnectionStatus(c.client.getClientName(), true, null, c.player.getId());
			}
		}
	}

	protected synchronized void removeClient(ServerThread client) {
		ClientPlayer clientPlayer = null;
		Iterator<ClientPlayer> iter = clients.iterator();

		while (iter.hasNext()) {
			ClientPlayer c = iter.next();
			if (c.client == client) {
				clientPlayer = c;
				if(c.player.getId() % 2 == 0) {
					teamAPlayers--;
				}else {
					teamBPlayers--;
				}
				iter.remove();
				log.log(Level.INFO, "Removed client " + c.client.getClientName() + " from " + getName());
			}
		}
		if (clients.size() > 0) {
			sendConnectionStatus(client, false, "left the room " + getName(), clientPlayer.player.getId());
		} else {
			cleanupEmptyRoom();
		}
	}

	private void cleanupEmptyRoom() {
		// If name is null it's already been closed. And don't close the Lobby
		if (name == null || name.equalsIgnoreCase(SocketServer.LOBBY)) {
			return;
		}
		try {
			log.log(Level.INFO, "Closing empty room: " + name);
			close();
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	protected void joinRoom(String room, ServerThread client) {
		server.joinRoom(room, client);
		state = GameState.LOBBY;
		log.log(Level.INFO, "Game is in Lobby state");
	}

	protected void joinLobby(ServerThread client) {
		server.joinLobby(client);
		state = GameState.LOBBY;
	}

	/***
	 * Helper function to process messages to trigger different functionality.
	 * 
	 * @param message The original message being sent
	 * @param client  The sender of the message (since they'll be the ones
	 *                triggering the actions)
	 */
	private String processCommands(String message, ServerThread client) {
		String response = null;
		try {
			if (message.indexOf(COMMAND_TRIGGER) > -1) {
				String[] comm = message.split(COMMAND_TRIGGER);
				log.log(Level.INFO, message);
				String part1 = comm[1];
				String[] comm2 = part1.split(" ");
				String command = comm2[0];
				ClientPlayer clientPlayer = null;
				if (command != null) {
					command = command.toLowerCase();
				}
				String roomName;
				switch (command) {
				case CREATE_ROOM:
					roomName = comm2[1];
					clientPlayer = getClientPlayer(client);
					if (clientPlayer != null) {
						createRoom(roomName, client);
					}
					break;
				case JOIN_ROOM:
					roomName = comm2[1];
					joinRoom(roomName, client);
					break;
				case READY:
					if (name.equals("Lobby")) {
						response = "ready is not valid for Lobby! Join a new room!";
						break;
					}

					clientPlayer = getClientPlayer(client);
					if (clientPlayer != null) {
						clientPlayer.player.setReady(true);
						readyCheck();
					}
					response = "Ready to go!";
					break;
				default:
					// not a command, let's fix this function from eating messages
					response = message;
					break;
				}
			} else {
				response = message;
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return response;
	}

	protected void sendConnectionStatus(ServerThread client, boolean isConnect, String message, int userId) {
		Iterator<ClientPlayer> iter = clients.iterator();
		while (iter.hasNext()) {
			ClientPlayer c = iter.next();
			boolean messageSent = c.client.sendConnectionStatus(client.getClientName(), isConnect, message, userId);
			if (!messageSent) {
				iter.remove();
				log.log(Level.INFO, "Removed client " + c.client.getId());
			}
		}
	}

	private void readyCheck() {
		Iterator<ClientPlayer> iter = clients.iterator();
		int total = clients.size();
		int ready = 0;
		while (iter.hasNext()) {
			ClientPlayer cp = iter.next();
			if (cp != null && cp.player.isReady()) {
				ready++;
			}
		}
		if (ready >= total) {
			
			Iterator<ClientPlayer> cpIter = clients.iterator();
			while(cpIter.hasNext()) {
				ClientPlayer c = cpIter.next();
				
				if(c.player.getId() % 2 == 0) {
					teamAPlayers++;
				}else {
					teamBPlayers++;
				}
			}
			// start
			System.out.println("Everyone's ready, let's do this!");
			state = GameState.GAME;
			broadcastHP(-1, MAX_HP);
			broadcastSetPlayersActive();
			broadcastGameState();
			currentNS = System.nanoTime();
			prevNS = currentNS;
			log.log(Level.INFO, "Game has begun in room " + name);
		}
	}

	/***
	 * Takes a sender and a message and broadcasts the message to all clients in
	 * this room. Client is mostly passed for command purposes but we can also use
	 * it to extract other client info.
	 * 
	 * @param sender  The client sending the message
	 * @param message The message to broadcast inside the room
	 */
	protected void sendMessage(ServerThread sender, String message) {
		log.log(Level.INFO, getName() + ": Sending message to " + clients.size() + " clients");
		message = processCommands(message, sender);
		if (message == null) {
			// it was a command, don't broadcast
			return;
		}
		Iterator<ClientPlayer> iter = clients.iterator();
		while (iter.hasNext()) {
			ClientPlayer client = iter.next();
			boolean messageSent = client.client.send(sender.getClientName(), message);
			if (!messageSent) {
				iter.remove();
				log.log(Level.INFO, "Removed client " + client.client.getId());
			}
		}
	}

	/**
	 * Broadcasts this client/player direction to all connected clients/players
	 * 
	 * @param sender
	 * @param dir
	 */
	protected void sendDirectionSync(ServerThread sender, Point dir) {
		if (state != GameState.GAME)
			return;

		boolean changed = false;
		// first we'll find the clientPlayer that sent their direction
		// and update the server-side instance of their direction
		Iterator<ClientPlayer> iter = clients.iterator();
		Player p = null;
		while (iter.hasNext()) {
			ClientPlayer client = iter.next();
			// update only our server reference for this client
			// if we don't have this "if" it'll update all clients (meaning everyone will
			// move in sync)
			if (client.client == sender) {
				changed = client.player.setDirection(dir.x, dir.y);
				p = client.player;
				break;
			}
		}
		// if the direction is "changed" (it should be, but check anyway)
		// then we'll broadcast the change in direction to all clients
		// so their local movement reflects correctly
		if (changed && p != null) {
			iter = clients.iterator();
			while (iter.hasNext()) {
				ClientPlayer client = iter.next();
				boolean messageSent = client.client.sendDirection(p.getId(), dir);

				if (!messageSent) {
					iter.remove();
					log.log(Level.INFO, "Removed client " + client.client.getId());
				}
			}

		}
	}

	/**
	 * Broadcasts this client/player position to all connected clients/players
	 * 
	 * @param sender
	 * @param pos
	 */
	protected void sendPositionSync(int clientId, Point pos) {
		Iterator<ClientPlayer> iter = clients.iterator();
		while (iter.hasNext()) {
			ClientPlayer client = iter.next();
			boolean messageSent = client.client.sendPosition(clientId, pos);
			if (!messageSent) {
				iter.remove();
				log.log(Level.INFO, "Removed client " + client.client.getId());
			}
		}
	}

	protected void sendSyncProjectile(Projectile proj) {
		Iterator<ClientPlayer> iter = clients.iterator();
		while (iter.hasNext()) {
			ClientPlayer client = iter.next();
			client.client.sendSyncProjectile(proj);
		}
	}

	protected void sendRemoveProjectile(int id) {
		Iterator<ClientPlayer> iter = clients.iterator();
		while (iter.hasNext()) {
			ClientPlayer client = iter.next();

			client.client.syncRemoveProjectile(id);
		}
	}

	public List<String> getRooms(String search) {
		return server.getRooms(search);
	}

	/***
	 * Will attempt to migrate any remaining clients to the Lobby room. Will then
	 * set references to null and should be eligible for garbage collection
	 */
	@Override
	public void close() throws Exception {
		int clientCount = clients.size();
		if (clientCount > 0) {
			log.log(Level.INFO, "Migrating " + clients.size() + " to Lobby");
			Iterator<ClientPlayer> iter = clients.iterator();
			Room lobby = server.getLobby();
			while (iter.hasNext()) {
				ClientPlayer client = iter.next();
				lobby.addClient(client.client);
				iter.remove();
			}
			log.log(Level.INFO, "Done Migrating " + clients.size() + " to Lobby");
		}
		server.cleanupRoom(this);
		name = null;
		isRunning = false;
		// should be eligible for garbage collection now
	}

	@Override
	public void awake() {
		// TODO Auto-generated method stub

	}

	@Override
	public void start() {
		// TODO Auto-generated method stub
		log.log(Level.INFO, getName() + " start called");
	}

	long frame = 0;

	void checkPositionSync(ClientPlayer cp) {
		// determine the maximum syncing needed
		// you do NOT need it every frame, if you do it could cause network congestion
		// and
		// lots of bandwidth that doesn't need to be utilized
		if (frame % 120 == 0) {// sync every 120 frames (i.e., if 60 fps that's every 2 seconds)
			// check if it's worth sycning the position
			// again this is to save unnecessary data transfer
			if (cp.player.changedPosition()) {
				sendPositionSync(cp.player.getId(), cp.player.getPosition());
			}
		}

	}

	// TODO fix update
	@Override
	public void update() {
		if (state != GameState.GAME)
			timeLeft = ROUND_TIME;

		prevNS = currentNS;
		currentNS = System.nanoTime();
		timeLeft -= (currentNS - prevNS);

		if ((timeLeft / MINUTE_NANO) < minutesLeft && state == GameState.GAME) {
			minutesLeft--;
			broadcastTimeLeft();
		}

		if (timeLeft <= 0 && state != GameState.END) {
			EndGame();
			projectiles.clear();
			return;
		}

		
		Iterator<Projectile> pIter = projectiles.iterator();
		while (pIter.hasNext()) {
			Projectile p = pIter.next();

			if (p != null && p.isActive()) {
				int projId = p.getId();

				p.move();

				List<Integer> targetIds = p.getCollidingPlayers(clients);
				if (p.passedScreenBounds(gameAreaSize)) {
					ClientPlayer cp = getClientPlayerById(projId);

					cp.setHasFired(false);
					sendRemoveProjectile(projId);
					pIter.remove();
				} else if (targetIds.size() > 0) {
					for (int id : targetIds) {
						ClientPlayer cp = getClientPlayerById(id);
						cp.player.setHP(cp.player.getHP() - 1);
						broadcastHP(cp.player.getId(), cp.player.getHP());
						log.log(Level.INFO, cp.client.getClientName() + " was hit!");
						if(!cp.player.HealthCheck()) {
							broadcastDeadPlayer(cp);
							if(cp.player.getTeam() == TEAM_A) {
								teamBScore++;
							}else {
								teamAScore++;
							}
							
							broadcastScores(teamAScore, teamBScore);
							
							sendMessage(cp.client, cp.client.getClientName() + " is out!");
							
							if(teamAPlayers == teamBScore || teamBPlayers == teamAScore) {
								EndGame();
								
								projectiles.clear();
								
								return;
							}
						}else {
							sendMessage(cp.client, cp.client.getClientName() + " was hit!");
						}
					}
					
					ClientPlayer cp = getClientPlayerById(projId);			
					
					cp.setHasFired(false);
					sendRemoveProjectile(projId);
					if(pIter != null)
						pIter.remove();
				}
			}
		}
		
		Iterator<ClientPlayer> iter = clients.iterator();
		while (iter.hasNext()) {
			ClientPlayer p = iter.next();
			if (p != null && p.player.isActive()) {
				// have the server-side player calc their potential new position
				p.player.move();
				int passedBounds = p.player.passedScreenBounds(gameAreaSize);

				switch (passedBounds) {
				case 1:
					p.player.setPosition(new Point(p.player.getPosition().x, p.player.getSize().y));// North
					break;

				case 2:
					p.player.setPosition(
					new Point(gameAreaSize.width - p.player.getSize().x, p.player.getPosition().y));// East
					break;

				case 3:
					p.player.setPosition(
					new Point(p.player.getPosition().x, gameAreaSize.height - p.player.getSize().y));// South
					break;

				case 4:
					p.player.setPosition(new Point(p.player.getSize().x, p.player.getPosition().y));// West
					break;
				}

				if (passedBounds > 0) {
					sendPositionSync(p.player.getId(), p.player.getPosition());
				}

				// determine if we should sync this player's position to all other players
				checkPositionSync(p);
			}
		}
	}

	private void EndGame() {
		state = GameState.END;
		broadcastGameState();
		broadcastSetPlayersInactive();
		try {
			TimeUnit.SECONDS.sleep(5L);
		} catch (InterruptedException e) {
			log.log(Level.INFO, "Sleeping for 5 seconds before moving back into Lobby.");
		}
		
		state = GameState.LOBBY;
		broadcastGameState();
		teamAScore = 0;
		teamBScore = 0;
		
		broadcastScores(teamAScore, teamBScore);
		
		Iterator<ClientPlayer> ite = clients.iterator();
		while(ite.hasNext()) {
			ClientPlayer cpReady = ite.next();
			cpReady.player.setReady(false);
		}
		
		teamAPlayers = 0;
		teamBPlayers = 0;	
	}

	private ClientPlayer getClientPlayerById(int playerId) {
		Iterator<ClientPlayer> iter = clients.iterator();
		while (iter.hasNext()) {
			ClientPlayer p = iter.next();
			if (p != null) {
				if (p.player.getId() == playerId) {
					return p;
				}
			}
		}

		return null;
	}

	private void broadcastScores(int teamAScore, int teamBScore) {
		Iterator<ClientPlayer> iter = clients.iterator();
		while (iter.hasNext()) {
			ClientPlayer c = iter.next();
			c.client.setScores(teamAScore, teamBScore);
		}
	}
	
	private void broadcastDeadPlayer(ClientPlayer cp) {
		cp.player.setActive(false);
			
		Iterator<ClientPlayer> iter = clients.iterator();
		while (iter.hasNext()) {
			ClientPlayer c = iter.next();
			c.client.sendDisablePlayer(cp.player.getId(), cp.client.getClientName());
		}
	}

	private void broadcastHP(int id, int hp) {
		Iterator<ClientPlayer> iter = clients.iterator();
		while (iter.hasNext()) {
			ClientPlayer c = iter.next();
			c.client.sendHP(id, hp);
		}
	}

	private void broadcastTimeLeft() {
		Iterator<ClientPlayer> iter = clients.iterator();
		while (iter.hasNext()) {
			ClientPlayer c = iter.next();
			c.client.sendTimeLeft(timeLeft);
			log.log(Level.INFO, timeLeft / MINUTE_NANO + " minutes left");
		}
	}

	private void broadcastGameState() {
		Iterator<ClientPlayer> iter = clients.iterator();
		while (iter.hasNext()) {
			ClientPlayer c = iter.next();
			c.client.sendGameState(state);
			log.log(Level.INFO, "Sending client " + c.player.getId() + " game status " + state.toString());
		}
	}

	private void broadcastSetPlayersInactive() {
		Iterator<ClientPlayer> iter = clients.iterator();
		while (iter.hasNext()) {
			ClientPlayer c = iter.next();
			c.player.setActive(false);
			c.client.sendActiveStatus(false);
			log.log(Level.INFO, "Set client " + c.player.getId() + " inactive!");
		}
	}

	private void broadcastSetPlayersActive() {
		Iterator<ClientPlayer> iter = clients.iterator();
		while (iter.hasNext()) {
			ClientPlayer c = iter.next();
			c.player.setActive(true);
			c.client.sendActiveStatus(true);
			log.log(Level.INFO, "Set client " + c.player.getId() + " active!");
		}
	}

	// don't call this more than once per frame
	private void nextFrame() {
		// we'll do basic frame tracking so we can trigger events
		// less frequently than each frame
		// update frame counter and prevent overflow
		if (Long.MAX_VALUE - 5 <= frame) {
			frame = Long.MIN_VALUE;
		}
		frame++;
	}

	@Override
	public void lateUpdate() {
		nextFrame();
	}

	@Override
	public void quit() {
		// don't call close here
		log.log(Level.WARNING, getName() + " quit() ");
	}

	@Override
	public void attachListeners() {
		// no listeners either since server side receives no input
	}

	public static Dimension getDimensions() {
		return gameAreaSize;
	}

	@Override
	public void draw(Graphics g) {
		// TODO Auto-generated method stub

	}

	public static long getMinute() {
		return MINUTE_NANO;
	}

	public void getSyncBullet(ServerThread client) {
		Iterator<ClientPlayer> iter = clients.iterator();
		while (iter.hasNext()) {
			ClientPlayer cp = iter.next();

			if (cp.client == client && !cp.hasFired()) {
				cp.setHasFired(true);
				int pt = cp.player.getTeam();
				int xdir = pt == 1 ? -1 : 1;

				Projectile newProj = new Projectile(pt, cp.player.getId(), xdir,
						new Point(cp.player.getPosition().x, cp.player.getPosition().y));

				projectiles.add(newProj);

				sendSyncProjectile(newProj);
			}
		}
	}

}