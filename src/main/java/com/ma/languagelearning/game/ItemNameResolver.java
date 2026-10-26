package com.ma.languagelearning.game;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ma.languagelearning.config.MllConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves item stacks into the registry key used by the ledger and the word the player has
 * to say.
 *
 * <p>The word is the item's own display name — {@code "Cobblestone"}, {@code "Oak Slab"} —
 * not a stemmed base word. Minecraft already ships those names in every language it
 * supports, so {@link #translatedName(ItemStack, String)} reads the official
 * {@code lang/<locale>.json} straight out of the client resource pack. That is what makes
 * the popup show the real foreign word for every vanilla item without a curated dictionary.</p>
 */
public final class ItemNameResolver implements WordResolver {

    private static final Logger LOG = LoggerFactory.getLogger("malanguagelearning");
    private static final Gson GSON = new Gson();

    /** locale -> (translation key -> text). Language files are big; load each one once. */
    private static final Map<String, Map<String, String>> LANG_CACHE = new ConcurrentHashMap<>();

    /**
     * Registry id of the item, e.g. {@code "minecraft:cobblestone"}.
     *
     * <p>Falls back to the item's translation key for anything the registry does not know
     * (which in practice only happens for stacks built outside a level).</p>
     */
    @Override
    public String keyFor(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        try {
            Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (id != null) {
                return id.toString();
            }
        } catch (Exception e) {
            LOG.debug("[malanguagelearning] registry lookup failed for item", e);
        }
        try {
            return stack.getItem().getDescriptionId();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The English display name of the stack, e.g. {@code "Cobblestone"}.
     *
     * <p>Returns null when the item is ignored by config, unnamed, or shorter than
     * {@link MllConfig#minWordLength} — one-letter names are noise, not words.</p>
     */
    @Override
    public String wordFor(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        MllConfig cfg = MllConfig.get();

        String key = keyFor(stack);
        if (cfg.ignoredItems != null && key != null && cfg.ignoredItems.contains(key)) {
            return null;
        }

        String name = hoverName(stack);
        if (name == null) {
            return null;
        }
        name = name.strip();
        if (name.isEmpty()) {
            return null;
        }
        if (name.codePointCount(0, name.length()) < Math.max(1, cfg.minWordLength)) {
            return null;
        }
        return name;
    }

    /**
     * The official Minecraft translation of this stack's name in the given client locale.
     *
     * @param stack    the stack to name
     * @param mcLocale the language file code, e.g. {@code "ja_jp"}, {@code "de_de"}
     * @return the translated name, or the hover name when no translation exists
     */
    public String translatedName(ItemStack stack, String mcLocale) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        String fallback = hoverName(stack);
        if (fallback == null) {
            fallback = "";
        }
        if (mcLocale == null || mcLocale.isBlank()) {
            return fallback;
        }
        Map<String, String> table = languageTable(mcLocale);
        if (table.isEmpty()) {
            return fallback;
        }
        String descriptionId;
        try {
            descriptionId = stack.getItem().getDescriptionId();
        } catch (Exception e) {
            return fallback;
        }
        String translated = table.get(descriptionId);
        return translated == null || translated.isBlank() ? fallback : translated;
    }

    /**
     * The name Minecraft itself would render in the player's current language, i.e. the
     * loaded {@link Language} table. Useful when the wanted locale is already active.
     */
    public String currentLanguageName(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        String fallback = hoverName(stack);
        if (fallback == null) {
            fallback = "";
        }
        try {
            String key = stack.getItem().getDescriptionId();
            return Language.getInstance().getOrDefault(key, fallback);
        } catch (Exception e) {
            return fallback;
        }
    }

    /** Drop cached language tables (called after a resource reload). */
    public static void clearCaches() {
        LANG_CACHE.clear();
    }

    // ------------------------------------------------------------------ internals

    private static String hoverName(ItemStack stack) {
        try {
            return stack.getHoverName().getString();
        } catch (Exception e) {
            LOG.debug("[malanguagelearning] could not resolve hover name", e);
            return null;
        }
    }

    /**
     * Reads {@code lang/<locale>.json} out of the client resource manager.
     *
     * <p>In 26.3 the language files are not bundled in the dev artifact, so they are only
     * available at runtime through the resource manager — hence the defensive handling.</p>
     */
    private static Map<String, String> languageTable(String mcLocale) {
        String locale = mcLocale.toLowerCase(Locale.ROOT).replace('-', '_');
        Map<String, String> cached = LANG_CACHE.get(locale);
        if (cached != null) {
            return cached;
        }
        Map<String, String> table = loadLanguageFile(locale);
        LANG_CACHE.put(locale, table);
        return table;
    }

    private static Map<String, String> loadLanguageFile(String locale) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) {
                return Map.of();
            }
            Identifier id = Identifier.fromNamespaceAndPath("minecraft", "lang/" + locale + ".json");
            Optional<Resource> resource = mc.getResourceManager().getResource(id);
            if (resource.isEmpty()) {
                Identifier fallbackId = Identifier.fromNamespaceAndPath("minecraft", "lang/" + baseOf(locale) + ".json");
                resource = mc.getResourceManager().getResource(fallbackId);
                if (resource.isEmpty()) {
                    LOG.warn("[malanguagelearning] no language file for locale {}", locale);
                    return Map.of();
                }
            }
            try (InputStream in = resource.get().open();
                 Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                JsonElement root = JsonParser.parseReader(reader);
                if (root == null || !root.isJsonObject()) {
                    return Map.of();
                }
                JsonObject object = root.getAsJsonObject();
                Map<String, String> table = new HashMap<>(Math.max(16, object.size()));
                for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                    JsonElement value = entry.getValue();
                    if (value != null && value.isJsonPrimitive()) {
                        table.put(entry.getKey(), value.getAsString());
                    }
                }
                LOG.debug("[malanguagelearning] loaded {} translations for {}", table.size(), locale);
                return table;
            }
        } catch (Exception e) {
            LOG.error("[malanguagelearning] failed to read language file {}", locale, e);
            return Map.of();
        }
    }

    /** {@code "pt_br"} -> {@code "pt"}; {@code "de_de"} -> {@code "de"}. */
    private static String baseOf(String locale) {
        int underscore = locale.indexOf('_');
        return underscore > 0 ? locale.substring(0, underscore) : locale;
    }
}
