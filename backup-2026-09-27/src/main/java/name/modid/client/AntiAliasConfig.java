package name.modid.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.EnumControllerBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public class AntiAliasConfig {

    public enum AntiAliasMode {
        NONE(null),
        FXAA("anti-alias:fxaa"),
        SMAA("anti-alias:smaa");

        public final Identifier postEffect;

        AntiAliasMode(String postEffect) {
            this.postEffect = postEffect == null ? null : Identifier.parse(postEffect);
        }
    }

    public static AntiAliasMode currentMode = AntiAliasMode.NONE;

    // --- FILE SAVING LOGIC ---
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("anti-alias.json");

    private static class ConfigData {
        AntiAliasMode mode = AntiAliasMode.NONE;
    }

    public static void load() {
        if (Files.exists(CONFIG_PATH)) {
            try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
                ConfigData data = GSON.fromJson(reader, ConfigData.class);
                if (data != null && data.mode != null) {
                    currentMode = data.mode;
                }
            } catch (Exception e) {
                System.err.println("Failed to load Anti-Alias config!");
                e.printStackTrace();
            }
        }
    }

    public static void save() {
        try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
            ConfigData data = new ConfigData();
            data.mode = currentMode;
            GSON.toJson(data, writer);
        } catch (Exception e) {
            System.err.println("Failed to save Anti-Alias config!");
            e.printStackTrace();
        }
    }

    public static boolean isShaderActive() {
        return false;
    }

    // --- YACL FALLBACK UI ---
    public static Screen createConfigScreen(Screen parent) {
        return YetAnotherConfigLib.createBuilder()
                .title(Component.literal("Anti-Alias Settings"))
                .category(ConfigCategory.createBuilder()
                        .name(Component.literal("General"))
                        .option(Option.<AntiAliasMode>createBuilder()
                                .name(Component.literal("Anti-Aliasing Method"))
                                .description(OptionDescription.of(Component.literal("Select your preferred post-processing filter.")))
                                .binding(
                                        AntiAliasMode.NONE,
                                        () -> currentMode,
                                        newValue -> currentMode = newValue
                                )
                                .controller(opt -> EnumControllerBuilder.create(opt).enumClass(AntiAliasMode.class))
                                .build())
                        .build())
                .save(AntiAliasConfig::save) // Instantly triggers our universal file writer when "Done" is clicked
                .build()
                .generateScreen(parent);
    }
}