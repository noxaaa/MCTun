package dev.mctun.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.mctun.MctunMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ConfigIo {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ConfigIo() {
    }

    public static ClientConfig loadClient() {
        return load("mctun-client.json", ClientConfig.class, ClientConfig.defaults());
    }

    public static ServerConfig loadServer() {
        return load("mctun-server.json", ServerConfig.class, ServerConfig.defaults());
    }

    private static <T> T load(String fileName, Class<T> type, T defaults) {
        Path path = FabricLoader.getInstance().getConfigDir().resolve(fileName);
        if (Files.notExists(path)) {
            writeDefaults(path, defaults);
            return defaults;
        }

        try (Reader reader = Files.newBufferedReader(path)) {
            T value = GSON.fromJson(reader, type);
            return value == null ? defaults : value;
        } catch (IOException | RuntimeException ex) {
            MctunMod.LOGGER.warn("Failed to load {}, using defaults", path, ex);
            return defaults;
        }
    }

    private static void writeDefaults(Path path, Object defaults) {
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(defaults, writer);
            }
        } catch (IOException ex) {
            MctunMod.LOGGER.warn("Failed to write default config {}", path, ex);
        }
    }
}
