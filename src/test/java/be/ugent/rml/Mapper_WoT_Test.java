package be.ugent.rml;

import be.ugent.rml.term.NamedNode;
import be.ugent.rml.term.Term;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class Mapper_WoT_Test extends TestCore {
    @Test
    public void evaluate_essence_wot_support() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(8000), 0);
        server.createContext("/trashcans", new Mapper_WoT_Test.TrashCansFileHandler());
        server.setExecutor(null); // creates a default executor
        server.start();

        HashMap<Term, String> outPaths = new HashMap<Term, String>();
        outPaths.put(new NamedNode("http://example.com/rules/#TargetDump"), "./web-of-things/essence/out-local-file.nt");
        outPaths.put(new NamedNode("rmlmapper://default.store"), "./web-of-things/essence/out-default.nq");
        doMapping("./web-of-things/essence/mapping.ttl", outPaths, "./web-of-things/essence/private-security-data.ttl");

        server.stop(0);
    }

    @Test
    public void evaluate_irail_stations_wot_support() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(8000), 0);
        server.createContext("/redirect", new Mapper_WoT_Test.IRailStationHandler1());
        server.createContext("/stations", new Mapper_WoT_Test.IRailStationHandler2());
        server.setExecutor(null); // creates a default executor
        server.start();

        HashMap<Term, String> outPaths = new HashMap<Term, String>();
        outPaths.put(new NamedNode("rmlmapper://default.store"), "./web-of-things/irail-stations/out-default.nq");
        doMapping("./web-of-things/irail-stations/mapping.ttl", outPaths);

        server.stop(0);
    }

    static class TrashCansFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            String response = "couldn't load trashcan JSON file";
            try {
                response = Utils.fileToString(Utils.getFile("./web-of-things/essence/iot-sensors.json"));
            } catch (IOException ex) {
                ex.printStackTrace();
            }
            List<String> contentType = new ArrayList<>();
            contentType.add("application/json");
            List<String> key = t.getRequestHeaders().get("apikey");

            // Check API key
            try {
                if (key.get(0).equals("123456789")) {
                    t.getResponseHeaders().put("Content-Type", contentType);
                    t.sendResponseHeaders(200, response.length());
                    OutputStream os = t.getResponseBody();
                    os.write(response.getBytes());
                    os.close();
                }
                // Wrong API key
                else {
                    t.sendResponseHeaders(401, response.length());
                }
            }
            // No API key provided
            catch (IndexOutOfBoundsException e) {
                t.sendResponseHeaders(401, response.length());
            }

        }
    }

    static class IRailStationHandler1 implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {

            // Redirect HTTP 302
                List<String> newLocation = new ArrayList<>();
                String response = "Redirected to /stations";
                newLocation.add("http://" + t.getLocalAddress().getHostName() + ":" + t.getLocalAddress().getPort() + "/stations");
                System.out.println(newLocation);
                t.getResponseHeaders().put("Location", newLocation);
                t.sendResponseHeaders(302, response.length());
        }
    }

    static class IRailStationHandler2 implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            String response = "couldn't load iRail stations JSON file";
            try {
                response = Utils.fileToString(Utils.getFile("./web-of-things/irail-stations/stations.json"));
            } catch (IOException ex) {
                ex.printStackTrace();
            }
            List<String> contentType = new ArrayList<>();
            contentType.add("application/json");

            // Return stations if not redirected
            t.getResponseHeaders().put("Content-Type", contentType);
            t.sendResponseHeaders(200, response.length());
            OutputStream os = t.getResponseBody();
            os.write(response.getBytes());
            os.close();
        }
    }

    @Test
    public void test_bearer_authentication_mocked() throws IOException {
        String testcaseDirPath = "./web-of-things/bearer-security-scheme-mocked/%s";
        // Mock server
        HttpServer server = HttpServer.create(new InetSocketAddress(8000), 0);

        server.createContext("/api", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                this.validateRequestHeaders(exchange.getRequestHeaders());
                String response = "Couldn't load JSON file";
                try {
                    String filePath = String.format(testcaseDirPath, "input.json");
                    response = Utils.fileToString(Utils.getFile(filePath));
                } catch (IOException ex) {
                    ex.printStackTrace();
                }
                List<String> contentType = new ArrayList<>();
                contentType.add("application/json");

                // Return response if not redirected
                exchange.getResponseHeaders().put("Content-Type", contentType);
                exchange.sendResponseHeaders(200, response.length());
                OutputStream os = exchange.getResponseBody();
                os.write(response.getBytes());
                os.close();
            }

            private void validateRequestHeaders(Headers requestHeaders) {
                // Assert that request header is not empty
                assert !requestHeaders.isEmpty();
                List<String> authorizationHeaders = requestHeaders.get("Authorization");
                // Assert the Authorization-header is present in the request
                assert !authorizationHeaders.isEmpty();
                String authorizationHeader = authorizationHeaders.get(0);
                // Assert that the bearer value is correct
                assert authorizationHeader.equals("Bearer s3cr3tb34r3r");
            }
        });

        server.setExecutor(null); // creates a default executor
        server.start();
        // TODO: create expected output: output.nq
        doMapping(String.format(testcaseDirPath, "mapping.ttl"), String.format(testcaseDirPath, "out-default.nq"));
        server.stop(0);
    }

}
