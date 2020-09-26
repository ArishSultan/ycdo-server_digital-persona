package dev.mark;

import java.util.Base64;
import org.bson.Document;
import io.javalin.Javalin;
import java.util.ArrayList;
import java.io.IOException;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import com.digitalpersona.uareu.Fmd;
import java.awt.image.DataBufferByte;
import com.mongodb.client.MongoClients;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import com.mongodb.client.MongoCollection;
import com.digitalpersona.uareu.UareUGlobal;
import com.digitalpersona.uareu.UareUException;
import org.bson.types.ObjectId;

public class Main {
    private static final HashMap<ObjectId, Fmd> fmdMap = new HashMap<>();
    private static final HashMap<ObjectId, Fmd> usersFmdMap = new HashMap<>();

    private static String createFMD(final String image) throws IOException, UareUException {
        final byte[] bytes = Base64.getDecoder()
                .decode(image.getBytes(StandardCharsets.UTF_8));

        if (bytes != null) {
            final BufferedImage buffer = ImageIO.read(new ByteArrayInputStream(bytes));

            if (buffer != null) {
                final Fmd fmd = UareUGlobal.GetEngine().CreateFmd(
                        ((DataBufferByte) buffer.getRaster().getDataBuffer()).getData(),
                        buffer.getWidth(), buffer.getHeight(), 500, 0, 1, Fmd.Format.ISO_19794_2_2005
                );

                return Base64.getEncoder().encodeToString(fmd.getData());
            }
        }

        return null;
    }

    private static void _loadPatientFmd() {
        fmdMap.clear();

        final MongoCollection<? extends Document> patients = MongoClients.create("mongodb://localhost:27017")
                .getDatabase("ycdo-hcc__db").getCollection("patients");

        patients.find().forEach((Consumer<Document>) consumer -> {
            String data = (String) ((Document) consumer).get("fmd");
            if (data == null) return;

            try {
                fmdMap.put((ObjectId) ((Document) consumer).get("_id"), UareUGlobal.GetImporter().ImportFmd(
                        Base64.getDecoder().decode(data),
                        Fmd.Format.ISO_19794_2_2005, Fmd.Format.ISO_19794_2_2005
                ));
            } catch (Exception ex) {
                System.out.println(ex);
            }
        });
    }

    private static void _loadUsersFmd() {
        usersFmdMap.clear();

        final MongoCollection<? extends Document> users = MongoClients.create("mongodb://localhost:27017")
                .getDatabase("ycdo-hcc__db").getCollection("users-fmds");

        users.find().forEach((Consumer<Document>) consumer -> {

            String data = (String) ((Document) consumer).get("fmd");
            if (data == null) return;

            try {
                usersFmdMap.put((ObjectId) ((Document) consumer).get("user"), UareUGlobal.GetImporter().ImportFmd(
                        Base64.getDecoder().decode(data),
                        Fmd.Format.ISO_19794_2_2005, Fmd.Format.ISO_19794_2_2005
                ));
            } catch (Exception ex) {
                System.err.println(ex);
            }
        });
    }

    public static void main(String[] args) {
        Javalin.create(config -> {
            config.enableCorsForAllOrigins();
            config.wsFactoryConfig(wsFactory -> {
                wsFactory.getPolicy().setMaxTextMessageSize(5000000);
                wsFactory.getPolicy().setMaxBinaryMessageBufferSize(5000000);
            });
        }).ws("/fingerprint", server -> server.onMessage(handler -> {
            try {
                final String fmd = createFMD(handler.message());
                if (fmd != null) handler.send(fmd);
                else throw new Exception();
            } catch (Exception e) {
                handler.send("INVALID_INPUT");
            }
        })).post("/match", handler -> {
            _loadPatientFmd();
            final String fingerprint = handler.formParam("finger");
            System.out.println(fingerprint);

            final ArrayList<ObjectId> ids = new ArrayList<>();
            final ArrayList<Integer> scores = new ArrayList<>();

            for (Map.Entry<ObjectId, Fmd> item: fmdMap.entrySet()) {
                final int score = UareUGlobal.GetEngine().Compare(UareUGlobal.GetImporter().ImportFmd(
                        Base64.getDecoder().decode(fingerprint),
                        Fmd.Format.ISO_19794_2_2005,
                        Fmd.Format.ISO_19794_2_2005
                ), 0, item.getValue(), 0);

                if (score < 2147483) {
                    scores.add(score);
                    ids.add(item.getKey());
                }

            }

            int index = 0;
            if (scores.size() > 0) {
                int min = scores.get(0);

                for (int i = 1; i < scores.size(); ++i) {
                    if (scores.get(i) < min) {
                        min = scores.get(i);
                        index = i;
                    }
                }
            } else return;

            handler.res.getWriter().write(ids.get(index).toHexString());
        }).post("/match-user", handler -> {
            _loadUsersFmd();

            final String fingerprint = handler.formParam("finger");
            System.out.println(fingerprint);

            final ArrayList<ObjectId> ids = new ArrayList<>();
            final ArrayList<Integer> scores = new ArrayList<>();

            for (Map.Entry<ObjectId, Fmd> item: usersFmdMap.entrySet()) {
                final int score = UareUGlobal.GetEngine().Compare(UareUGlobal.GetImporter().ImportFmd(
                        Base64.getDecoder().decode(fingerprint),
                        Fmd.Format.ISO_19794_2_2005,
                        Fmd.Format.ISO_19794_2_2005
                ), 0, item.getValue(), 0);

                if (score < 2147483) {
                    scores.add(score);
                    ids.add(item.getKey());
                }
            }

            int index = 0;
            if (scores.size() > 0) {
                int min = scores.get(0);

                for (int i = 1; i < scores.size(); ++i) {
                    if (scores.get(i) < min) {
                        min = scores.get(i);
                        index = i;
                    }
                }
            } else return;

            handler.res.getWriter().write(ids.get(index).toHexString());
        }).start(7070);
    }
}
