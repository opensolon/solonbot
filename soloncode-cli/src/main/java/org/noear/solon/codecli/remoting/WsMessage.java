package org.noear.solon.codecli.remoting;

import lombok.Data;

@Data
public class WsMessage {

    String input;

    String sessionId;

    String model;

    String agent;

    String cwd;
}
