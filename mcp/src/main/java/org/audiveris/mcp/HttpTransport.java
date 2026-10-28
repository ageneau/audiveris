//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                        H t t p T r a n s p o r t                              //
//                                                                                                //
//------------------------------------------------------------------------------------------------//
// <editor-fold defaultstate="collapsed" desc="hdr">
//
//  Copyright © Audiveris 2026. All rights reserved.
//
//  This program is free software: you can redistribute it and/or modify it under the terms of the
//  GNU Affero General Public License as published by the Free Software Foundation, either version
//  3 of the License, or (at your option) any later version.
//
//  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
//  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
//  See the GNU Affero General Public License for more details.
//
//  You should have received a copy of the GNU Affero General Public License along with this
//  program.  If not, see <http://www.gnu.org/licenses/>.
//------------------------------------------------------------------------------------------------//
// </editor-fold>
package org.audiveris.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.eclipse.jetty.ee11.servlet.FilterHolder;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.ee11.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.EnumSet;
import java.util.List;

/**
 * Streamable HTTP transport backed by an embedded Jetty server.
 */
public class HttpTransport
{
    private static final Logger logger = LoggerFactory.getLogger(HttpTransport.class);

    private final Server server;

    private final McpSyncServer mcpServer;

    public HttpTransport (McpJsonMapper mapper,
                          List<SyncToolSpecification> tools,
                          String host,
                          int port,
                          String path,
                          String token)
        throws Exception
    {
        final HttpServletStreamableServerTransportProvider provider =
                HttpServletStreamableServerTransportProvider.builder()
                        .jsonMapper(mapper)
                        .mcpEndpoint(path)
                        .build();

        mcpServer = McpServer.sync(provider)
                .serverInfo("audiveris-mcp", "1.0.0")
                .instructions(
                        "Audiveris optical music recognition server. "
                                + "Use list_steps before processing a score.")
                .capabilities(ServerCapabilities.builder()
                        .tools(false)
                        .logging()
                        .build())
                .tools(tools)
                .build();

        final ServletContextHandler context = new ServletContextHandler(
                ServletContextHandler.NO_SESSIONS);
        context.setContextPath("/");

        if ((token != null) && !token.isBlank()) {
            context.addFilter(
                    new FilterHolder(new BearerFilter(token)),
                    "/*",
                    EnumSet.of(DispatcherType.REQUEST));
        }

        context.addServlet(new ServletHolder(provider), "/*");

        server = new Server(new InetSocketAddress(host, port));
        server.setHandler(context);
        server.start();

        logger.info("HTTP MCP endpoint listening on http://{}:{}{}", host, port, path);
    }

    public McpSyncServer mcpServer ()
    {
        return mcpServer;
    }

    public void stop ()
    {
        try {
            mcpServer.closeGracefully();
        } finally {
            try {
                server.stop();
            } catch (Exception ex) {
                logger.warn("Error stopping HTTP server: {}", ex.toString());
            }
        }
    }

    private static class BearerFilter
            implements Filter
    {
        private final byte[] expected;

        BearerFilter (String token)
        {
            expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public void init (FilterConfig filterConfig)
            throws ServletException
        {
        }

        @Override
        public void doFilter (ServletRequest request,
                              ServletResponse response,
                              FilterChain chain)
            throws IOException, ServletException
        {
            final String header = ((HttpServletRequest) request).getHeader("Authorization");
            final byte[] actual = header == null
                    ? new byte[0]
                    : header.getBytes(StandardCharsets.UTF_8);

            if (!MessageDigest.isEqual(expected, actual)) {
                ((HttpServletResponse) response).sendError(
                        HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }

            chain.doFilter(request, response);
        }

        @Override
        public void destroy ()
        {
        }
    }
}
