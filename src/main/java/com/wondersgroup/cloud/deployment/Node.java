package com.wondersgroup.cloud.deployment;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.alibaba.druid.util.StringUtils;
import com.wondersgroup.cloud.deployment.utils.URL;

public class Node {
	private Log logger = LogFactory.getLog(Node.class);
	private String ip;// 使用绑定的网卡对应IP 替代
	private NetworkCommander commander;
	private Map<Integer, IReceiveHandler> handlerMap = new HashMap<Integer, IReceiveHandler>(
			4);

	public Node() {
		try {
			Properties props = new Properties();
			props.load(Thread.currentThread().getContextClassLoader()
					.getResourceAsStream("node.properties"));
			URL url = URL.valueOf(props.getProperty("register.url"));

			ip = props.getProperty("network.bind.ip.name");
			if (ip == null || ip.equals("")) {
				String networkInterface = props
						.getProperty("network.bind.interface.name");
				ip = this.getBindIP(networkInterface);
			}
			commander = new MutilGroupNetworkCommander(url, this);
		} catch (IOException ex) {
			throw new DeployException("初始化异常:" + ex.getMessage(), ex);
		}
	}

	private String getBindIP(String networkInterface) throws SocketException {
		Enumeration<NetworkInterface> netInterfaces = NetworkInterface
				.getNetworkInterfaces();
		while (netInterfaces.hasMoreElements()) {
			NetworkInterface ni = netInterfaces.nextElement();
			if (networkInterface.equals(ni.getName())) {
				Enumeration<InetAddress> ips = ni.getInetAddresses();
				while (ips.hasMoreElements()) {
					// 直接返回绑定的第一个IP
					String bindIp = ips.nextElement().getHostAddress();
					logger.info("bindIp:::" + bindIp);
					return bindIp;
				}
			}
		}
		throw new DeployException("没有匹配的IP地址");
	}

	public void run() {
		Thread deamon = new Thread(new Runnable() {

			@Override
			public void run() {
				// 不停接受 外部传来的消息，交给注册的处理器处理
				while (true) {
					// recv
					commander.acceptMsg();
				}
			}

		});
		deamon.setName("Node BackEnd Thread.");
		deamon.setDaemon(true);
		deamon.start();
	}

	public void executeCommand(ICommand command) {
		// 2 .如果没有注册join的处理器 就认为是 工作机 可以继续
		// this.workerIPs.size() == 0 &&
		logger.info("cmmd:::" + handlerMap.containsKey(Node.JOIN));
		if (handlerMap.containsKey(Node.JOIN)) {
			return;
		}
		if (command.getKey() == Node.DEPLOY) {
			logger.info("start real deploy flow.");
			this.fireNodeEvent(command.toString(), this.ip, command);
		} else {
			String ip = this.ip;
			if (!StringUtils.isEmpty(command.getSrcIp())) {
				ip = command.getSrcIp();
			}
			logger.info("cmmd::normal cmmd1:::" + ip);
			logger.info("cmmd::normal cmmd2:::" + command.getSrcIp());
			commander.sendMsg(ip, command);
		}
	}

	public boolean isClient() {
		return handlerMap.containsKey(Node.CLOSE);
	}

	public void registerReceiveHandler(int key, IReceiveHandler receiveHandler) {
		handlerMap.put(key, receiveHandler);
	}

	// 换成2进制 剩下4位做状态更替使用 上面32-4全部做状态名称；
	public static final int STATUS_BITS = Integer.SIZE - 4;
	public static final int STATUS_CHANGE = (1 << STATUS_BITS) - 1;

	public static final int INIT = 0 << STATUS_BITS;
	public static final int JOIN = 1 << STATUS_BITS;
	public static final int NEXT = 2 << STATUS_BITS;
	// 这是一个整体
	public static final int DEPLOY = 3 << STATUS_BITS;
	public static final int PREPARE = 4 << STATUS_BITS;
	public static final int CLOSE = 5 << STATUS_BITS;
	public static final int DELETE = 6 << STATUS_BITS;
	public static final int TRANSPORT = 7 << STATUS_BITS;
	public static final int START = 8 << STATUS_BITS;
	public static final int TEST = 9 << STATUS_BITS;
	// 定时任务
	public static final int DEPLOY_SCHEDULE = 10 << STATUS_BITS;

	public static final int SUCCESS = 1;
	public static final int FAILURE = 2;
	public static final String Local = "localhost";

	public static int runStateOf(int c) {
		return c & ~STATUS_CHANGE;
	}

	public static int stateDetailOf(int c) {
		return c & STATUS_CHANGE;
	}

	public static int incrementState(int state) {
		int _state = state >>> STATUS_BITS;
		_state++;
		return _state << STATUS_BITS;
	}

	public static boolean isGoon(int state) {
		int _state = state >>> STATUS_BITS;
		return _state < Node.TEST >>> STATUS_BITS;
	}

	public static int compareOf(int state1, int state2) {
		int _real1 = state1 >>> STATUS_BITS;
		int _real2 = state2 >>> STATUS_BITS;
		return _real1 - _real2;
	}

	public static String debugState(int state) {
		String result = "nothing";

		if (Node.runStateOf(state) == INIT) {
			result = "init";
		} else if (Node.runStateOf(state) == JOIN) {
			result = "join";
		} else if (Node.runStateOf(state) == NEXT) {
			result = "next";
		} else if (Node.runStateOf(state) == DEPLOY) {
			result = "deploy";
		} else if (Node.runStateOf(state) == CLOSE) {
			result = "close";
		} else if (Node.runStateOf(state) == DELETE) {
			result = "delete";
		} else if (Node.runStateOf(state) == TRANSPORT) {
			result = "transport";
		} else if (Node.runStateOf(state) == START) {
			result = "start";
		} else if (Node.runStateOf(state) == TEST) {
			result = "test";
		} else if (Node.runStateOf(state) == DEPLOY_SCHEDULE) {
			result = "schedule";
		} else if (Node.runStateOf(state) == Node.PREPARE) {
			result = "prepare";
		}

		if (Node.stateDetailOf(state) == Node.SUCCESS) {
			result += "-1";
		} else if (Node.stateDetailOf(state) == Node.FAILURE) {
			result += "-0";
		}
		return result;
	}

	public void changeToSuccess(int c) {
		c &= SUCCESS;
	};

	public void changeToFAILURE(int c) {
		c &= FAILURE;
	};

	public int selectKey(String msg) {
		if (msg.indexOf(",") > 0) {
			return Integer.valueOf(msg.substring(0, msg.indexOf(",")));
		} else {
			return Integer.valueOf(msg);
		}
	}

	public void handleReceive(String msg, String srcIp) {
		// 如果远端IP与本身IP是一样的 那就不做处理
		logger.info("client enter....." + srcIp + "-------------" + this.ip);
		if (srcIp.equals(this.ip)) {
			logger.info("omg!!");
			return;
		}
		int _key = this.selectKey(msg);
		this.callHandler(_key, msg, srcIp);
		if (msg.indexOf(",") > 0) {
			logger.info("handleReceive.fireNodeEvent:1__"
					+ Node.debugState(Integer.valueOf(msg.substring(0,
							msg.indexOf(",")))));
		} else {
			logger.info("handleReceive.fireNodeEvent:2__"
					+ Node.debugState(Integer.valueOf(msg)));
		}
		this.fireNodeEvent(msg, srcIp, _key);
	}

	public void callHandler(int _key, String msg, String srcIp) {
		IReceiveHandler handler = handlerMap.get(_key);
		if (handler != null) {
			logger.info("client..call handler");
			handler.handle(msg, srcIp);
		}
	}
	
	public void fireNodeEvent(String msg, String srcIp, Object... params) {
		for (INodeListener listener : nodeListeners) {
			listener.fireNodeEvent(msg, srcIp, params);
		}
	}

	private List<INodeListener> nodeListeners = new ArrayList<INodeListener>(2);

	public void registerNodeListener(INodeListener nodeListener) {
		nodeListeners.add(nodeListener);
	}

	public String getIp() {
		return ip;
	}

}
