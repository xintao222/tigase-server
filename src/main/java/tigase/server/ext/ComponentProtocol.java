/*
 * Tigase Jabber/XMPP Server
 * Copyright (C) 2004-2008 "Artur Hefczyc" <artur.hefczyc@tigase.org>
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3 of the License.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program. Look for COPYING file in the top folder.
 * If not, see http://www.gnu.org/licenses/.
 * 
 * $Rev$
 * Last modified by $Author$
 * $Date$
 */

package tigase.server.ext;

import tigase.server.ext.handlers.UnknownXMLNSStreamOpenHandler;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.script.Bindings;
import tigase.db.ComponentRepository;
import tigase.net.ConnectionType;
import tigase.net.SocketType;
import tigase.server.ConnectionManager;
import tigase.server.Packet;
import tigase.server.ext.handlers.BindProcessor;
import tigase.server.ext.handlers.ComponentAcceptStreamOpenHandler;
import tigase.server.ext.handlers.ComponentConnectStreamOpenHandler;
import tigase.server.ext.handlers.HandshakeProcessor;
import tigase.server.ext.handlers.JabberClientStreamOpenHandler;
import tigase.server.ext.handlers.SASLProcessor;
import tigase.server.ext.handlers.StartTLSProcessor;
import tigase.server.ext.handlers.StreamFeaturesProcessor;
import tigase.stats.StatisticsList;
import tigase.xml.Element;
import tigase.xmpp.XMPPIOService;

/**
 * Created: Sep 30, 2009 8:28:13 PM
 *
 * @author <a href="mailto:artur.hefczyc@tigase.org">Artur Hefczyc</a>
 * @version $Rev$
 */
public class ComponentProtocol
		extends ConnectionManager<XMPPIOService<List<ComponentConnection>>>
		implements ComponentProtocolHandler {

  /**
   * Variable <code>log</code> is a class logger.
   */
  private static final Logger log =
    Logger.getLogger(ComponentProtocol.class.getName());

	public static final String EXTCOMP_REPO_CLASS_PROPERTY = "--extcomp-repo-class";
	public static final String EXTCOMP_REPO_CLASS_PROP_KEY = "repository-class";
	public static final String EXTCOMP_REPO_CLASS_PROP_VAL =
					"tigase.server.ext.CompDBRepository";
	public static final String EXTCOMP_BIND_HOSTNAMES = "--bind-ext-hostnames";
	public static final String PACK_ROUTED_KEY = "pack-routed";
	public static final String RETURN_SERVICE_DISCO_KEY = "service-disco";
	public static final boolean RETURN_SERVICE_DISCO_VAL = true;
	public static final String IDENTITY_TYPE_KEY = "identity-type";
	public static final String IDENTITY_TYPE_VAL = "generic";
	public boolean PACK_ROUTED_VAL = false;

	/**
	 * A map keeping all active connections by a connection JID or domain
	 * name.
	 * Since for each domain we can have 1..N connections the Map value
	 * is a List of connections.
	 */
	private Map<String, ArrayList<ComponentConnection>> connections =
			new ConcurrentSkipListMap<String, ArrayList<ComponentConnection>>();
	private Map<String, StreamOpenHandler> streamOpenHandlers =
			new LinkedHashMap<String, StreamOpenHandler>();
	/**
	 * List of processors which should handle all traffic incoming from the
	 * network. In most cases if not all, these processors handle just
	 * protocol traffic, all the rest traffic should be passed on to MR.
	 */
	private Map<String, ExtProcessor> processors = new LinkedHashMap<String, ExtProcessor>();
	private UnknownXMLNSStreamOpenHandler unknownXMLNSHandler =
			new UnknownXMLNSStreamOpenHandler();
	private ComponentRepository<CompRepoItem> repo = null;
	private String identity_type = IDENTITY_TYPE_VAL;
	private String[] hostnamesToBind = null;
	//private ServiceEntity serviceEntity = null;


	public ComponentProtocol() {
		super();
		StreamOpenHandler handler = new JabberClientStreamOpenHandler();
		if (handler.getXMLNSs() != null) {
			for (String xmlns : handler.getXMLNSs()) {
				streamOpenHandlers.put(xmlns, handler);
			}
		}
		handler = new ComponentAcceptStreamOpenHandler();
		if (handler.getXMLNSs() != null) {
			for (String xmlns : handler.getXMLNSs()) {
				streamOpenHandlers.put(xmlns, handler);
			}
		}
		handler = new ComponentConnectStreamOpenHandler();
		if (handler.getXMLNSs() != null) {
			for (String xmlns : handler.getXMLNSs()) {
				streamOpenHandlers.put(xmlns, handler);
			}
		}
	}

	/**
	 * This method can be overwritten in extending classes to get a different
	 * packets distribution to different threads. For PubSub, probably better
	 * packets distribution to different threads would be based on the
	 * sender address rather then destination address.
	 * @param packet
	 * @return
	 */
	@Override
	public int hashCodeForPacket(Packet packet) {
		if (packet.getElemTo() != null) {
			return packet.getElemTo().hashCode();
		}
		if (packet.getTo() != null) {
			return packet.getTo().hashCode();
		}
		return super.hashCodeForPacket(packet);
	}

	@Override
	public int processingThreads() {
		return Runtime.getRuntime().availableProcessors();
	}

	@Override
	public Queue<Packet> processSocketData(XMPPIOService<List<ComponentConnection>> serv) {
		Packet p = null;
		Queue<Packet> results = new LinkedList<Packet>();
		while ((p = serv.getReceivedPackets().poll()) != null) {
			if (log.isLoggable(Level.FINEST)) {
				log.finest("Processing socket data: " + p.getStringData());
			}
			boolean processed = false;
			for (ExtProcessor proc : processors.values()) {
				processed |= proc.process(p, serv, this, results);
				writePacketsToSocket(serv, results);
			}
			if (!processed) {
				if (p.isRouted()) {
					p = p.unpackRouted();
				} // end of if (p.isRouted())
				addOutPacket(p);
			}
		} // end of while ()
		return null;
	}

	@Override
	protected XMPPIOService<List<ComponentConnection>> getXMPPIOService(Packet p) {
		XMPPIOService<List<ComponentConnection>> result = null;
		String hostname = p.getElemToHost();
		ArrayList<ComponentConnection> conns = connections.get(hostname);
		if (conns != null) {
			for (ComponentConnection componentConnection : conns) {
				XMPPIOService<List<ComponentConnection>> serv = componentConnection.getService();
				if (serv != null) {
					if (serv.isConnected()) {
						result = serv;
					} else {
						log.info("Service is not connected for connection for hostname: " + hostname);
					}
				} else {
					log.info("Service is null for connection for hostname: " + hostname);
				}
				if (result != null) {
					break;
				}
			}
		} else {
			log.info("No ext connection for hostname: " + hostname);
		}
		return result;
	}

	@Override
	public void xmppStreamClosed(XMPPIOService<List<ComponentConnection>> serv) {	}

	@Override
	public String xmppStreamOpened(XMPPIOService<List<ComponentConnection>> serv,
			Map<String, String> attribs) {
		if (log.isLoggable(Level.FINEST)) {
			log.finest("Stream opened: " + serv.getRemoteAddress() +
					", xmlns: " + attribs.get("xmlns") +
					", type: " + serv.connectionType().toString() +
					", uniqueId=" + serv.getUniqueId() +
					", to=" + attribs.get("to"));
		}
		String s_xmlns = attribs.get("xmlns");
		String result = null;
		StreamOpenHandler handler = streamOpenHandlers.get(s_xmlns);
		if (handler == null || s_xmlns == null) {
			log.finest("unknownXMLNSHandler is processing request");
			result = unknownXMLNSHandler.streamOpened(serv, attribs, this);
		} else {
			log.finest(handler.getClass().getName() + " is processing request");
			result = handler.streamOpened(serv, attribs, this);
		}
		if (log.isLoggable(Level.FINEST)) {
			log.finest("Sending back: " + result);
		}
		return result;
	}

	@Override
	public void serviceStarted(XMPPIOService<List<ComponentConnection>> serv) {
		super.serviceStarted(serv);
		String xmlns =
				((CompRepoItem)serv.getSessionData().get(REPO_ITEM_KEY)).getXMLNS();
		if (log.isLoggable(Level.FINEST)) {
			log.finest("Connection started: " + serv.getRemoteAddress() +
					", xmlns: " + xmlns +
					", type: " + serv.connectionType().toString() +
					", id=" + serv.getUniqueId());
		}
		StreamOpenHandler handler = streamOpenHandlers.get(xmlns);
		String result = null;
		if (handler == null) {
			// Well, that's a but, we should not be here...
			log.fine("XMLNS not set, accepting a new connection with xmlns auto-detection.");
		} else {
			if (log.isLoggable(Level.FINEST)) {
				log.finest("cid: " + (String)serv.getSessionData().get("cid") +
						", sending: " + result);
			}
			result = handler.serviceStarted(serv);
		}
		if (result != null) {
			serv.xmppStreamOpen(result);
		}
	}

	@Override
	protected long getMaxInactiveTime() {
		return 1000*24*HOUR;
	}

	@Override
	protected XMPPIOService<List<ComponentConnection>> getXMPPIOServiceInstance() {
		return new XMPPIOService<List<ComponentConnection>>();
	}

	@Override
	public String getDiscoDescription() {
		return "External component";
	}

	@Override
	public String getDiscoCategory() {
		return identity_type;
	}

	private synchronized void addComponentConnection(String hostname,
			XMPPIOService<List<ComponentConnection>> s) {
		ComponentConnection conn = new ComponentConnection(hostname, s);
		List<ComponentConnection> refObject = s.getRefObject();
		if (refObject == null) {
			refObject = new CopyOnWriteArrayList<ComponentConnection>();
			s.setRefObject(refObject);
		}
		refObject.add(conn);
		ArrayList<ComponentConnection> conns = connections.get(hostname);
		if (conns == null) {
			conns = new ArrayList<ComponentConnection>();
			connections.put(hostname, conns);
		}
		// Not very optimal, however this does not happen (should not) very often
		// and the data collections is optimized for fast object retrieval
		// by index (round robin balance for example)
		boolean result = conns.add(conn);
		if (result) {
			log.finer("A new component connection added for: " + hostname);
		} else {
			log.fine("A new component connection NOT added for: " + hostname);
		}
	}

	private synchronized boolean removeComponentConnection(String hostname,
			ComponentConnection conn) {
		boolean result = false;
		ArrayList<ComponentConnection> conns = connections.get(hostname);
		if (conns != null) {
			// This is slow, however this does not happen (should not) very often
			// and the data collections is optimized for fast object retrieval
			// by index (round robin balance for example)
			boolean removed = conns.remove(conn);
			if (removed) {
				log.finer("A component connection removed for: " + hostname);
			} else {
				log.fine("A component connection NOT removed for: " + hostname);
			}
			for (ComponentConnection compCon : conns) {
				XMPPIOService<List<ComponentConnection>> serv = compCon.getService();
				if (serv != null && serv.isConnected()) {
					// There is still an active connection for this host
					result = true;
				} else {
					log.warning("Null or disconnected service for ComponentConnection for host: " +
							hostname);
				}
			}
		} else {
			log.warning("That should not happen, ComponentConnection is not null but the collection is: " +
					hostname);
		}
		return result;
	}

	@Override
	public boolean serviceStopped(XMPPIOService<List<ComponentConnection>> service) {
		boolean result = super.serviceStopped(service);
		if (result) {
			Map<String, Object> sessionData = service.getSessionData();
			String hostname = (String)sessionData.get(XMPPIOService.HOSTNAME_KEY);
			if (hostname != null && !hostname.isEmpty()) {
				List<ComponentConnection> conns = service.getRefObject();
				if (conns != null) {
					for (ComponentConnection conn : conns) {
						boolean moreConnections = removeComponentConnection(conn.getDomain(), conn);
						if (!moreConnections) {
							removeRoutings(conn.getDomain());
						}
					}
				} else {
					// Nothing to do, let's log this however.
					log.finer("Closing XMPPIOService has not yet set ComponentConnection as RefObject: " +
							hostname + ", id: " + service.getUniqueId());
				}
			} else {
				// Stopped service which hasn't sent initial stream open yet
				log.finer("Stopped service which hasn't sent initial stream open yet" +
						service.getUniqueId());
			}
			ConnectionType type = service.connectionType();
			if (type == ConnectionType.connect) {
				addWaitingTask(sessionData);
				//reconnectService(sessionData, connectionDelay);
			} // end of if (type == ConnectionType.connect)
		}
		return result;
	}

	private void removeRoutings(String hostname) {
		String[] routings = new String[]{hostname, ".*@" + hostname, ".*\\." +
			hostname};
		updateRoutings(routings, false);
		//		removeRouting(serv.getRemoteHost());
		//String addr = (String)sessionData.get(PORT_REMOTE_HOST_PROP_KEY);
		if (log.isLoggable(Level.FINE)) {
			log.fine("Disonnected from: " + hostname);
		}
		updateServiceDiscoveryItem(hostname, null, "XEP-0114 disconnected", false);
	}

	@Override
	@SuppressWarnings({"unchecked"})
	public Map<String, Object> getDefaults(Map<String, Object> params) {
		Map<String, Object> defs = super.getDefaults(params);
		String repo_class = (String)params.get(EXTCOMP_REPO_CLASS_PROPERTY);
		if (repo_class == null) {
			repo_class = EXTCOMP_REPO_CLASS_PROP_VAL;
		}
		defs.put(EXTCOMP_REPO_CLASS_PROP_KEY, repo_class);
		try {
			repo = (ComponentRepository<CompRepoItem>) Class.forName(repo_class).newInstance();
			repo.getDefaults(defs, params);
		} catch (Exception e) {
			log.log(Level.SEVERE,
							"Can not instantiate items repository for class: " +
							repo_class, e);
		}
		defs.put(PACK_ROUTED_KEY, PACK_ROUTED_VAL);
		defs.put(RETURN_SERVICE_DISCO_KEY, RETURN_SERVICE_DISCO_VAL);
		defs.put(IDENTITY_TYPE_KEY, IDENTITY_TYPE_VAL);
		String bind_hostnames = (String)params.get(EXTCOMP_BIND_HOSTNAMES);
		if (bind_hostnames != null) {
			defs.put(EXTCOMP_BIND_HOSTNAMES_PROP_KEY, bind_hostnames.split(","));
		} else {
			defs.put(EXTCOMP_BIND_HOSTNAMES_PROP_KEY, new String[] {""});
		}
		return defs;
	}

	@Override
	@SuppressWarnings({"unchecked"})
	public void setProperties(Map<String, Object> properties) {
		identity_type = (String)properties.get(IDENTITY_TYPE_KEY);
		super.setProperties(properties);
		String repo_class = (String)properties.get(EXTCOMP_REPO_CLASS_PROP_KEY);
		try {
			ComponentRepository<CompRepoItem> repo_tmp =
					(ComponentRepository<CompRepoItem>)Class.forName(repo_class).
					newInstance();
			repo_tmp.setProperties(properties);
			repo = repo_tmp;
		} catch (Exception e) {
			log.log(Level.SEVERE, "Can not create items repository instance for class: " +
							repo_class, e);
		}
		// Activate all connections for which parameters are defined in the repository
		for (CompRepoItem repoItem : repo) {
			log.info("Loaded repoItem: " + repoItem.toString());
			if (repoItem.getPort() > 0) {
				Map<String, Object> port_props = new LinkedHashMap<String, Object>();
				port_props.put(PORT_KEY, repoItem.getPort());
				if (repoItem.getDomain() != null) {
					port_props.put(PORT_LOCAL_HOST_PROP_KEY, repoItem.getDomain());
				}
				if (repoItem.getRemoteHost() != null) {
					port_props.put(PORT_REMOTE_HOST_PROP_KEY, repoItem.getRemoteHost());
				}
				port_props.put(PORT_TYPE_PROP_KEY, repoItem.getConnectionType());
				port_props.put(PORT_SOCKET_PROP_KEY, SocketType.plain);
				port_props.put(PORT_IFC_PROP_KEY, PORT_IFC_PROP_VAL);
				port_props.put(MAX_RECONNECTS_PROP_KEY, (int)(120 * MINUTE));
				port_props.put(REPO_ITEM_KEY, repoItem);
				log.info("Starting connection: " + port_props.toString());
				addWaitingTask(port_props);
			}
		}
		hostnamesToBind = (String[])properties.get(EXTCOMP_BIND_HOSTNAMES_PROP_KEY);
		if (hostnamesToBind.length == 1 && hostnamesToBind[0].isEmpty()) {
			hostnamesToBind = null;
		}
		log.config("Hostnames to bind: " + Arrays.toString(hostnamesToBind));
		processors = new LinkedHashMap<String, ExtProcessor>();
		ExtProcessor proc = new HandshakeProcessor();
		processors.put(proc.getId(), proc);
		proc = new StreamFeaturesProcessor();
		processors.put(proc.getId(), proc);
		proc = new StartTLSProcessor();
		processors.put(proc.getId(), proc);
		proc = new SASLProcessor();
		processors.put(proc.getId(), proc);
		proc = new BindProcessor();
		processors.put(proc.getId(), proc);
	}

	@Override
	public void getStatistics(StatisticsList list) {
		// Warning size() for ConcurrentSkipListMap is very slow
		// unless we have a huge number of domains this should not be a problem though.
		list.add(getName(), "Number of external domains",
						connections.size(), Level.FINE);
		int size = 0;
		for (ArrayList<ComponentConnection> conns : connections.values()) {
			size += conns.size();
		}
		list.add(getName(), "Number of external component connections",	size, Level.FINER);
	}

	private void updateRoutings(String[] routings, boolean add) {
		if (add) {
			for (String route: routings) {
				try {
					addRegexRouting(route);
				} catch (Exception e) {
					log.warning("Can not add regex routing '" + route + "' : " + e);
				}
			}
		} else {
			for (String route: routings) {
				try {
					removeRegexRouting(route);
					log.fine("Removed routings: " + route);
				} catch (Exception e) {
					log.warning("Can not remove regex routing '" + route + "' : " + e);
				}
			}
		}
		log.finest("All regex routings: " + getRegexRoutings().toString());
	}

	@Override
	public void authenticated(XMPPIOService<List<ComponentConnection>> serv) {
		serv.getSessionData().put(AUTHENTICATED_KEY, Boolean.TRUE);
		String hostname = (String)serv.getSessionData().get(XMPPIOService.HOSTNAME_KEY);
		bindHostname(hostname, serv);
		if (hostnamesToBind != null) {
			serv.getSessionData().put(EXTCOMP_BIND_HOSTNAMES_PROP_KEY, hostnamesToBind);
			ExtProcessor proc = getProcessor("bind");
			if (proc != null) {
				Queue<Packet> results = new LinkedList<Packet>();
				proc.startProcessing(null, serv, this, results);
				writePacketsToSocket(serv, results);
			}
		}
	}

	@Override
	public void bindHostname(String hostname, XMPPIOService<List<ComponentConnection>> serv) {
		String[] routings = new String[] { hostname, ".*@" + hostname, ".*\\." + hostname };
		updateRoutings(routings, true);
		if (log.isLoggable(Level.FINE)) {
			log.fine("Authenticated: " + hostname);
		}
		updateServiceDiscoveryItem(hostname, null, "XEP-0114 connected", false);
		addComponentConnection(hostname, serv);
	}

	@Override
	public void unbindHostname(String hostname, XMPPIOService<List<ComponentConnection>> serv) {
		ArrayList<ComponentConnection> conns = connections.get(hostname);
		if (conns != null) {
			ComponentConnection conn = null;
			for (ComponentConnection componentConnection : conns) {
				if (componentConnection.getService() == serv) {
					conn = componentConnection;
				}
			}
			if (conn != null) {
				boolean moreConnections =
						removeComponentConnection(conn.getDomain(), conn);
				if (!moreConnections) {
					removeRoutings(conn.getDomain());
				}
			}
		}
	}

	@Override
	public CompRepoItem getCompRepoItem(String hostname) {
		return repo.getItem(hostname);
	}

	@Override
	public void initBindings(Bindings binds) {
		super.initBindings(binds);
		binds.put(ComponentRepository.COMP_REPO_BIND, repo);
	}

	@Override
	public List<Element> getStreamFeatures(XMPPIOService<List<ComponentConnection>> serv) {
		List<Element> results = new LinkedList<Element>();
		for (ExtProcessor proc : processors.values()) {
			List<Element> proc_res = proc.getStreamFeatures(serv, this);
			if (proc_res != null) {
				results.addAll(proc_res);
			}
		}
		return results;
	}

	@Override
	public ExtProcessor getProcessor(String key) {
		return processors.get(key);
	}

	@Override
	public StreamOpenHandler getStreamOpenHandler(String xmlns) {
		return streamOpenHandlers.get(xmlns);
	}

}