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

import java.io.InputStream;
import java.io.PrintStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

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

        if (!"stdio".equals(options.transport)) {
            System.err.println(
                    "audiveris-mcp: transport '" + options.transport
                            + "' is not implemented yet; use --transport stdio");
            System.exit(2);
            return;
        }

        try {
            HeadlessBootstrap.initialize(options.workspace);

            final Path workspace = options.workspace != null
                    ? options.workspace
                    : WellKnowns.DATA_FOLDER.resolve("mcp");
            Files.createDirectories(workspace);

            final McpJsonMapper mapper = McpJsonDefaults.getMapper();
            final OmrService service = new OmrService(workspace);
            final List<SyncToolSpecification> tools = new ArrayList<>(
                    DiagnosticTools.specs(mapper, new AudiverisFacade()));
            tools.addAll(SessionTools.specs(mapper, service));

            final StdioServerTransportProvider transport = new StdioServerTransportProvider(
                    mapper,
                    System.in,
                    protocolOut);

            final McpSyncServer server = McpServer.sync(transport)
                    .serverInfo(SERVER_NAME, SERVER_VERSION)
                    .instructions(
                            "Audiveris optical music recognition server. "
                                    + "Use list_steps before processing a score.")
                    .capabilities(ServerCapabilities.builder()
                            .tools(false)
                            .logging()
                            .build())
                    .tools(tools)
                    .build();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    service.closeAll();
                } finally {
                    server.closeGracefully();
                }
            }));

            // Keep the process alive while the STDIO transport is connected.
            Thread.currentThread().join();
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
        System.err.println("  --transport stdio    Transport to use (default: stdio)");
        System.err.println("  --workspace DIR      Workspace for inputs and outputs");
        System.err.println("  --help               Display this help and exit");
        System.err.println("  --version            Display the server version and exit");
    }

    private static class Options
    {
        String transport = "stdio";

        Path workspace;

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
