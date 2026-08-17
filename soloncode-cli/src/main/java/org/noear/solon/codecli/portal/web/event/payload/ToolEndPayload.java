package org.noear.solon.codecli.portal.web.event.payload;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolEndPayload implements Serializable {
    private String callId;
    private String reasonId;
    private String name;
    private String title;
    private String result;
    private boolean isError;
    private String diff;
    private Map<String, Object> args;
}
