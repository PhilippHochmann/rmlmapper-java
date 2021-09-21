package be.ugent.rml.access;

import org.bimserver.client.BimServerClient;
import org.bimserver.client.json.JsonBimServerClientFactory;
import org.bimserver.database.queries.om.DefaultQueries;
import org.bimserver.interfaces.objects.*;
import org.bimserver.shared.ChannelConnectionException;
import org.bimserver.shared.UsernamePasswordAuthenticationInfo;
import org.bimserver.shared.exceptions.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Map;
import java.util.Random;

public class BimServerAccess implements Access {

    private String address;
    private String username;
    private String password;
    private String ifcPath;
    private String query;

    public BimServerAccess(String address, String username, String password, String ifcPath, String query) {
        this.address = address;
        this.username = username;
        this.password = password;
        this.ifcPath = ifcPath;
        this.query = query;
    }

    /* Returns new project */
    private SProject checkinFile(BimServerClient client) throws PublicInterfaceNotFoundException, ServerException, UserException {
        try {
            String randomName = "Random " + new Random().nextLong();

            // Create a new project with a random name
            SProject project = client.getServiceInterface().addProject(randomName, "ifc2x3tc1");

            long poid = project.getOid();

            // This method is an easy way to find a compatible deserializer for the combination of the "ifc" file extension and this project. You can also get a specific deserializer if you want to.
            SDeserializerPluginConfiguration deserializer = client.getServiceInterface().getSuggestedDeserializerForExtension("ifc", poid);

            // Make sure you change this to a path to a local IFC file
            Path demoIfcFile = Paths.get(this.ifcPath);

            //client.bulkCheckin(poid, demoIfcFile, comment);
            SLongActionState state = client.checkinSync(poid, "test", deserializer.getOid(), false, demoIfcFile);

            if (state.getState() != SActionState.FINISHED) {
                System.out.println(state.getState());
            }

            return project;

        } catch (ServerException e) {
            e.printStackTrace();
        } catch (UserException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }

        return null;
    }

    public InputStream getInputStream() {
        // Connect to server
        // Creating a factory in a try statement, this makes sure the factory will be closed after use
        JsonBimServerClientFactory factory = null;
        try {
            factory = new JsonBimServerClientFactory(this.address);
        } catch (BimServerClientException e) {
            e.printStackTrace();
        }
        // Creating a client in a try statement, this makes sure the client will be closed after use
        BimServerClient client = null;
        try {
            client = factory.create(new UsernamePasswordAuthenticationInfo(username, password));
        } catch (ServiceException e) {
            e.printStackTrace();
        } catch (ChannelConnectionException e) {
            e.printStackTrace();
        }
        // Do something with the client
        SProject project = null;
        try {
            project = checkinFile(client);
        } catch (ServerException e) {
            e.printStackTrace();
        } catch (UserException e) {
            e.printStackTrace();
        }
        try {
            project = client.getServiceInterface().getProjectByPoid(project.getOid());
        } catch (ServerException e) {
            e.printStackTrace();
        } catch (UserException e) {
            e.printStackTrace();
        }
        SSerializerPluginConfiguration serializer = null;
        try {
            serializer = client.getServiceInterface().getSerializerByContentType("application/ifc");
        } catch (ServerException e) {
            e.printStackTrace();
        } catch (UserException e) {
            e.printStackTrace();
        }

        long topicId = 0; // True for syncing
        try {
            topicId = client.getServiceInterface().download(
                    Collections.singleton(project.getLastRevisionId()),

                    /*"{\n" +
                            "  \"type\": {\n" +
                            "    \"name\": \"IfcWall\",\n" +
                            "    \"includeAllSubTypes\": true\n" +
                            "  }\n" +
                            "}",*/
                    DefaultQueries.allAsString(),
                    serializer.getOid(),
                    false);
        } catch (ServerException e) {
            e.printStackTrace();
        } catch (UserException e) {
            e.printStackTrace();
        }
        InputStream result = null;
        try {
            result = client.getDownloadData(topicId);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return result;
    }

    @Override
    public Map<String, String> getDataTypes() {
        return null;
    }
}
