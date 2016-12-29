/*
 * This file is part of Mixin, licensed under the MIT License (MIT).
 *
 * Copyright (c) SpongePowered <https://www.spongepowered.org>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.spongepowered.asm.mixin.refmap;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.common.collect.Maps;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import net.minecraft.launchwrapper.Launch;

/**
 * Stores runtime information allowing field, method and type references which
 * cannot be hard remapped by the reobfuscation process to be remapped in a
 * "soft" manner at runtime. Refmaps are generated by the <em>Annotation
 * Processor</em> at compile time and must be bundled with an obfuscated binary
 * to allow obfuscated references in injectors and other String-defined targets
 * to be remapped to the target obfsucation environment as appropriate. If the
 * refmap is absent the environment is assumed to be deobfuscated (eg. dev-time)
 * and injections and other transformations will fail if this is not the case. 
 */
public final class ReferenceMapper implements Serializable {
    
    private static final long serialVersionUID = 2L;

    /**
     * Resource to attempt to load if no source is specified explicitly 
     */
    public static final String DEFAULT_RESOURCE = "mixin.refmap.json";
    
    /**
     * Passthrough mapper, used as failover 
     */
    public static final ReferenceMapper DEFAULT_MAPPER = new ReferenceMapper(true);
    
    /**
     * Log even more things
     */
    private static final Logger logger = LogManager.getLogger("mixin");

    /**
     * "Default" mappings. The set of mappings to use as "default" is specified
     * by the AP. Each entry is keyed by the owning mixin, with the value map
     * containing the actual remappings for each owner
     */
    private final Map<String, Map<String, String>> mappings = Maps.newHashMap();
    
    /**
     * All mapping sets, keyed by environment type, eg. "notch", "searge". The
     * format of each map within this map is the same as for {@link #mappings}
     */
    private final Map<String, Map<String, Map<String, String>>> data = Maps.newHashMap();
    
    /**
     * True if this refmap cannot be written. Only true for the
     * {@link #DEFAULT_MAPPER}
     */
    private final transient boolean readOnly; 
    
    /**
     * Current remapping context, used as the key into {@link data}
     */
    private transient String context = null;
    
    /**
     * Create an empty refmap
     */
    public ReferenceMapper() {
        this(false);
    }
    
    /**
     * Create a readonly refmap, only used by {@link #DEFAULT_MAPPER}
     * 
     * @param readOnly flag to indicate read-only
     */
    private ReferenceMapper(boolean readOnly) {
        this.readOnly = readOnly;
    }
    
    /**
     * Get the current context
     * 
     * @return current context key, can be null
     */
    public String getContext() {
        return this.context;
    }
    
    /**
     * Set the current remap context, can be null
     * 
     * @param context remap context
     */
    public void setContext(String context) {
        this.context = context;
    }
    
    /**
     * Remap a reference for the specified owning class in the current context
     * 
     * @param className Owner class
     * @param reference Reference to remap
     * @return remapped reference, returns original reference if not remapped
     */
    public String remap(String className, String reference) {
        return this.remapWithContext(this.context, className, reference);
    }
    
    /**
     * Remap a reference for the specified owning class in the specified context
     * 
     * @param context Remap context to use
     * @param className Owner class
     * @param reference Reference to remap
     * @return remapped reference, returns original reference if not remapped
     */
    public String remapWithContext(String context, String className, String reference) {
        Map<String, Map<String, String>> mappings = this.mappings;
        if (context != null) {
            mappings = this.data.get(context);
            if (mappings == null) {
                mappings = this.mappings;
            }
        }
        return this.remap(mappings, className, reference);
    }
    
    /**
     * Remap the things
     */
    private String remap(Map<String, Map<String, String>> mappings, String className, String reference) {
        if (className == null) {
            for (Map<String, String> mapping : mappings.values()) {
                if (mapping.containsKey(reference)) {
                    return mapping.get(reference);
                }
            }
        }
        
        Map<String, String> classMappings = mappings.get(className);
        if (classMappings == null) {
            return reference;
        }
        String remappedReference = classMappings.get(reference);
        return remappedReference != null ? remappedReference : reference;
    }
    
    /**
     * Add a mapping to this refmap
     * 
     * @param context Obfuscation context, can be null
     * @param className Class which owns this mapping, cannot be null
     * @param reference Reference to remap, cannot be null
     * @param newReference Remapped value, cannot be null
     * @return replaced value, per the contract of {@link Map#put}
     */
    public String addMapping(String context, String className, String reference, String newReference) {
        if (this.readOnly || reference == null || newReference == null || reference.equals(newReference)) {
            return null;
        }
        Map<String, Map<String, String>> mappings = this.mappings;
        if (context != null) {
            mappings = this.data.get(context);
            if (mappings == null) {
                mappings = Maps.newHashMap();
                this.data.put(context, mappings);
            }
        }
        Map<String, String> classMappings = mappings.get(className);
        if (classMappings == null) {
            classMappings = new HashMap<String, String>();
            mappings.put(className, classMappings);
        }
        return classMappings.put(reference, newReference);
    }
    
    /**
     * Write this refmap out to the specified writer
     * 
     * @param writer Writer to write to
     */
    public void write(Appendable writer) {
        new GsonBuilder().setPrettyPrinting().create().toJson(this, writer);
    }
    
    /**
     * Read a new refmap from the specified resource
     * 
     * @param resourcePath Resource to read from
     * @return new refmap or {@link #DEFAULT_MAPPER} if reading fails
     */
    public static ReferenceMapper read(String resourcePath) {
        Reader reader = null;
        try {
            InputStream resource = Launch.classLoader.getResourceAsStream(resourcePath);
            if (resource != null) {
                reader = new InputStreamReader(resource);
                return ReferenceMapper.readJson(reader);
            }
        } catch (JsonParseException ex) {
            ReferenceMapper.logger.error("Invalid REFMAP JSON in " + resourcePath + ": " + ex.getClass().getName() + " " + ex.getMessage());
        } catch (Exception ex) {
            ReferenceMapper.logger.error("Failed reading REFMAP JSON from " + resourcePath + ": " + ex.getClass().getName() + " " + ex.getMessage());
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ex) {
                    // don't really care
                }
            }
        }
        
        return ReferenceMapper.DEFAULT_MAPPER;
    }
    
    /**
     * Read a new refmap instance from the specified reader 
     * 
     * @param reader Reader to read from
     * @return new refmap
     */
    public static ReferenceMapper read(Reader reader) {
        try {
            return ReferenceMapper.readJson(reader);
        } catch (Exception ex) {
            return ReferenceMapper.DEFAULT_MAPPER;
        }
    }

    private static ReferenceMapper readJson(Reader reader) {
        return new Gson().fromJson(reader, ReferenceMapper.class);
    }
    
}
