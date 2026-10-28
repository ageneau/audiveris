//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                      M c p S e r v e r M a i n                                 //
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

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;

import org.audiveris.omr.WellKnowns;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

/**
 * Main entry point for the Audiveris MCP server.
 */
public class McpServerMain
{
    private static final String SERVER_NAME = "audiveris-mcp";

    private static final String SERVER_VERSION = "1.0.0";

    public static void main (String[] args)
    {
        final PrintStream protocolOut = System.out;

        // Any accidental Audiveris console output must not corrupt the STDIO protocol.
        System.setOut(new PrintStream(System.err, true));
        System.setProperty("java.awt.headless", "true");

        configureLogging();

        final Options options;

        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException ex) {
            System.err.println("audiveris-mcp: " + ex.getMessage());
            System.err.println("Use --help for usage.");
            System.exit(2);
            return;
        }

        if (options.help) {
            printUsage();
            return;
        }

        if (options.version) {
            System.err.println(SERVER_NAME + " " + SERVER_VERSION);
            return;
        }

        if (!Set.of("stdio", "http", "both").contains(options.transport)) {
            System.err.println(
                    "audiveris-mcp: unknown transport '" + options.transport
                            + "'; use stdio, http or both");
            System.exit(2);
            return;
        }

        final boolean useStdio = "stdio".equals(options.transport)
                || "both".equals(options.transport);
        final boolean useHttp = "http".equals(options.transport)
                || "both".equals(options.transport);

        if (useHttp) {
            try {
                if ((options.httpPort < 1) || (options.httpPort > 65535)) {
                    throw new IllegalArgumentException("--http-port must be between 1 and 65535");
                }

                if (!options.httpPath.startsWith("/")) {
                    throw new IllegalArgumentException("--http-path must start with '/'");
                }

                if (!InetAddress.getByName(options.httpHost).isLoopbackAddress()
                        && ((options.httpToken == null) || options.httpToken.isBlank())) {
                    throw new IllegalArgumentException(
                            "non-loopback HTTP bind requires --http-token or AUDIVERIS_MCP_TOKEN");
                }
            } catch (Exception ex) {
                System.err.println("audiveris-mcp: " + ex.getMessage());
                System.exit(2);
                return;
            }
        }

        final CountDownLatch stdinClosed = new CountDownLatch(1);

        try {
            HeadlessBootstrap.initialize(options.workspace);

            final Path workspace = options.workspace != null
                    ? options.workspace
                    : WellKnowns.DATA_FOLDER.resolve("mcp");
            Files.createDirectories(workspace);

            final McpJsonMapper mapper = McpJsonDefaults.getMapper();
            final OmrService service = new OmrService(workspace);
            final JobManager jobs = new JobManager(service);
            final List<SyncToolSpecification> tools = new ArrayList<>(
                    DiagnosticTools.specs(mapper, new AudiverisFacade()));
            tools.addAll(SessionTools.specs(mapper, service));
            tools.addAll(InspectionTools.specs(mapper, service));
            tools.addAll(EditTools.specs(mapper, service));
            tools.addAll(ConstantTools.specs(mapper));
            tools.addAll(ProcessingTools.specs(mapper, service, jobs));
            tools.addAll(JobTools.specs(mapper, service, jobs));

            final List<McpSyncServer> servers = new ArrayList<>();
            final List<HttpTransport> httpTransports = new ArrayList<>();

            if (useStdio) {
                final InputStream transportInput = new FilterInputStream(System.in)
                {
                    @Override
                    public int read ()
                        throws IOException
                    {
                        final int value = super.read();
                        if (value == -1) {
                            stdinClosed.countDown();
                        }
                        return value;
                    }

                    @Override
                    public int read (byte[] buffer,
                                     int offset,
                                     int length)
                        throws IOException
                    {
                        final int count = super.read(buffer, offset, length);
                        if (count == -1) {
                            stdinClosed.countDown();
                        }
                        return count;
                    }
                };

                final StdioServerTransportProvider transport = new StdioServerTransportProvider(
                        mapper,
                        transportInput,
                        protocolOut);

                servers.add(McpServer.sync(transport)
                        .serverInfo(SERVER_NAME, SERVER_VERSION)
                        .instructions(
                                "Audiveris optical music recognition server. "
                                        + "Use list_steps before processing a score.")
                        .capabilities(ServerCapabilities.builder()
                                .tools(false)
                                .logging()
                                .build())
                        .tools(tools)
                        .build());
            }

            if (useHttp) {
                httpTransports.add(new HttpTransport(
                        mapper,
                        tools,
                        options.httpHost,
                        options.httpPort,
                        options.httpPath,
                        options.httpToken));
            }

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    jobs.close();
                    service.closeAll();
                } finally {
                    for (HttpTransport http : httpTransports) {
                        http.stop();
                    }

                    for (McpSyncServer server : servers) {
                        server.closeGracefully();
                    }
                }
            }));

            if (useStdio) {
                // Keep the process alive while the STDIO transport is connected.
                stdinClosed.await();
                System.exit(0);
            } else {
                Thread.currentThread().join();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Throwable ex) {
            System.err.println("audiveris-mcp: fatal error: " + ex);
            ex.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void configureLogging ()
    {
        try {
            final URL resource = McpServerMain.class.getResource("/logback.xml");

            if (resource == null) {
                return;
            }

            final Path config = Files.createTempFile("audiveris-mcp-logback-", ".xml");

            try (InputStream in = resource.openStream()) {
                Files.copy(in, config, StandardCopyOption.REPLACE_EXISTING);
            }

            config.toFile().deleteOnExit();
            System.setProperty("logback.configurationFile", config.toAbsolutePath().toString());
        } catch (Exception ex) {
            System.err.println("audiveris-mcp: could not configure logging: " + ex);
        }
    }

    private static void printUsage ()
    {
        System.err.println("Usage: audiveris-mcp [OPTIONS]");
        System.err.println();
        System.err.println("Options:");
        System.err.println("  --transport MODE     stdio, http or both (default: stdio)");
        System.err.println("  --workspace DIR      Workspace for inputs and outputs");
        System.err.println("  --http-host HOST     HTTP bind address (default: 127.0.0.1)");
        System.err.println("  --http-port PORT     HTTP port (default: 8765)");
        System.err.println("  --http-path PATH     MCP endpoint path (default: /mcp)");
        System.err.println("  --http-token TOKEN   Optional bearer token");
        System.err.println("  --help               Display this help and exit");
        System.err.println("  --version            Display the server version and exit");
    }

    private static class Options
    {
        String transport = "stdio";

        Path workspace;

        String httpHost = "127.0.0.1";

        int httpPort = 8765;

        String httpPath = "/mcp";

        String httpToken = System.getenv("AUDIVERIS_MCP_TOKEN");

        boolean help;

        boolean version;

        static Options parse (String[] args)
        {
            final Options options = new Options();

            for (int i = 0; i < args.length; i++) {
                final String arg = args[i];

                switch (arg) {
                    case "--help", "-h" -> options.help = true;
                    case "--version", "-V" -> options.version = true;
                    case "--transport" -> options.transport = value(args, ++i, arg);
                    case "--workspace" -> options.workspace = Paths.get(
                            value(args, ++i, arg)).toAbsolutePath().normalize();
                    case "--http-host" -> options.httpHost = value(args, ++i, arg);
                    case "--http-port" -> options.httpPort = Integer.parseInt(
                            value(args, ++i, arg));
                    case "--http-path" -> options.httpPath = value(args, ++i, arg);
                    case "--http-token" -> options.httpToken = value(args, ++i, arg);
                    default -> throw new IllegalArgumentException("unknown option: " + arg);
                }
            }

            return options;
        }

        private static String value (String[] args,
                                     int index,
                                     String option)
        {
            if (index >= args.length) {
                throw new IllegalArgumentException("missing value for " + option);
            }

            return args[index];
        }
    }
}
