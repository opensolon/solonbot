package org.noear.soloncode.sdk;

import org.noear.soloncode.sdk.exceptions.SolonCodeSDKException;
import org.noear.soloncode.sdk.parsing.ParsedMessage;
import org.noear.soloncode.sdk.permission.ToolPermissionCallback;
import org.noear.soloncode.sdk.transport.CLIOptions;
import org.noear.soloncode.sdk.types.Message;

import java.util.Iterator;
import java.util.Map;

/**
 * Internal session contract used by the unified request-oriented client.
 *
 * <p>This deliberately does not expose the legacy connect/query facade. The concrete
 * implementation may still implement that facade while the new API is migrated, but
 * the unified client depends only on session execution primitives.</p>
 */
interface SolonCodeSession extends AutoCloseable {
    void connect(String initialPrompt) throws SolonCodeSDKException;
    void query(String prompt) throws SolonCodeSDKException;
    Iterator<ParsedMessage> receiveResponse();
    void interrupt() throws SolonCodeSDKException;
    void setModel(String model) throws SolonCodeSDKException;
    void setPermissionMode(String mode) throws SolonCodeSDKException;
    CLIOptions getOptions();
    String getCurrentModel();
    String getCurrentPermissionMode();
    Map<String, Object> getServerInfo();
    boolean isConnected();
    void setToolPermissionCallback(ToolPermissionCallback callback);
    ToolPermissionCallback getToolPermissionCallback();
    @Override
    void close();
}
