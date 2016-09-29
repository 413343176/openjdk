/*
 * Copyright (c) 2012, 2016, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package com.sun.tools.jdeps;

import static com.sun.tools.jdeps.Module.trace;
import static java.util.stream.Collectors.*;

import com.sun.tools.classfile.Dependency;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.module.Configuration;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleDescriptor.Exports;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.lang.module.ResolvedModule;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

public class JdepsConfiguration implements AutoCloseable {
    // the token for "all modules on the module path"
    public static final String ALL_MODULE_PATH = "ALL-MODULE-PATH";
    public static final String ALL_DEFAULT = "ALL-DEFAULT";
    public static final String MODULE_INFO = "module-info.class";

    private final SystemModuleFinder system;
    private final ModuleFinder finder;

    private final Map<String, Module> nameToModule = new LinkedHashMap<>();
    private final Map<String, Module> packageToModule = new HashMap<>();
    private final Map<String, List<Archive>> packageToUnnamedModule = new HashMap<>();

    private final List<Archive> classpathArchives = new ArrayList<>();
    private final List<Archive> initialArchives = new ArrayList<>();
    private final Set<Module> rootModules = new HashSet<>();
    private final Configuration configuration;
    private final Runtime.Version version;

    private JdepsConfiguration(SystemModuleFinder systemModulePath,
                               ModuleFinder finder,
                               Set<String> roots,
                               List<Path> classpaths,
                               List<Archive> initialArchives,
                               boolean allDefaultModules,
                               Runtime.Version version)
        throws IOException
    {
        trace("root: %s%n", roots);

        this.system = systemModulePath;
        this.finder = finder;
        this.version = version;

        // build root set for resolution
        Set<String> mods = new HashSet<>(roots);

        // add default modules to the root set
        // unnamed module
        if (!initialArchives.isEmpty() || !classpaths.isEmpty() ||
                roots.isEmpty() || allDefaultModules) {
            mods.addAll(systemModulePath.defaultSystemRoots());
        }

        this.configuration = Configuration.empty()
                .resolveRequires(finder, ModuleFinder.of(), mods);

        this.configuration.modules().stream()
                .map(ResolvedModule::reference)
                .forEach(this::addModuleReference);

        // packages in unnamed module
        initialArchives.forEach(archive -> {
            addPackagesInUnnamedModule(archive);
            this.initialArchives.add(archive);
        });

        // classpath archives
        for (Path p : classpaths) {
            if (Files.exists(p)) {
                Archive archive = Archive.getInstance(p, version);
                addPackagesInUnnamedModule(archive);
                classpathArchives.add(archive);
            }
        }

        // all roots specified in --add-modules or -m are included
        // as the initial set for analysis.
        roots.stream()
             .map(nameToModule::get)
             .forEach(this.rootModules::add);

        initProfiles();

        trace("resolved modules: %s%n", nameToModule.keySet().stream()
                .sorted().collect(joining("\n", "\n", "")));
    }

    private void initProfiles() {
        // other system modules are not observed and not added in nameToModule map
        Map<String, Module> systemModules =
            system.moduleNames()
                .collect(toMap(Function.identity(), (mn) -> {
                    Module m = nameToModule.get(mn);
                    if (m == null) {
                        ModuleReference mref = finder.find(mn).get();
                        m = toModule(mref);
                    }
                    return m;
                }));
        Profile.init(systemModules);
    }

    private void addModuleReference(ModuleReference mref) {
        Module module = toModule(mref);
        nameToModule.put(mref.descriptor().name(), module);
        mref.descriptor().packages()
            .forEach(pn -> packageToModule.putIfAbsent(pn, module));
    }

    private void addPackagesInUnnamedModule(Archive archive) {
        archive.reader().entries().stream()
               .filter(e -> e.endsWith(".class") && !e.equals(MODULE_INFO))
               .map(this::toPackageName)
               .distinct()
               .forEach(pn -> packageToUnnamedModule
                   .computeIfAbsent(pn, _n -> new ArrayList<>()).add(archive));
    }

    private String toPackageName(String name) {
        int i = name.lastIndexOf('/');
        return i > 0 ? name.replace('/', '.').substring(0, i) : "";
    }

    public Optional<Module> findModule(String name) {
        Objects.requireNonNull(name);
        Module m = nameToModule.get(name);
        return m!= null ? Optional.of(m) : Optional.empty();

    }

    public Optional<ModuleDescriptor> findModuleDescriptor(String name) {
        Objects.requireNonNull(name);
        Module m = nameToModule.get(name);
        return m!= null ? Optional.of(m.descriptor()) : Optional.empty();
    }

    boolean isSystem(Module m) {
        return system.find(m.name()).isPresent();
    }

    boolean isValidToken(String name) {
        return ALL_MODULE_PATH.equals(name) || ALL_DEFAULT.equals(name);
    }

    /**
     * Returns the modules that the given module can read
     */
    public Stream<Module> reads(Module module) {
        return configuration.findModule(module.name()).get()
            .reads().stream()
            .map(ResolvedModule::name)
            .map(nameToModule::get);
    }

    /**
     * Returns the list of packages that split between resolved module and
     * unnamed module
     */
    public Map<String, Set<String>> splitPackages() {
        Set<String> splitPkgs = packageToModule.keySet().stream()
                                       .filter(packageToUnnamedModule::containsKey)
                                       .collect(toSet());
        if (splitPkgs.isEmpty())
            return Collections.emptyMap();

        return splitPkgs.stream().collect(toMap(Function.identity(), (pn) -> {
            Set<String> sources = new LinkedHashSet<>();
            sources.add(packageToModule.get(pn).getModule().location().toString());
            packageToUnnamedModule.get(pn).stream()
                .map(Archive::getPathName)
                .forEach(sources::add);
            return sources;
        }));
    }

    /**
     * Returns an optional archive containing the given Location
     */
    public Optional<Archive> findClass(Dependency.Location location) {
        String name = location.getName();
        int i = name.lastIndexOf('/');
        String pn = i > 0 ? name.substring(0, i).replace('/', '.') : "";
        Archive archive = packageToModule.get(pn);
        if (archive != null) {
            return archive.contains(name + ".class")
                        ? Optional.of(archive)
                        : Optional.empty();
        }

        if (packageToUnnamedModule.containsKey(pn)) {
            return packageToUnnamedModule.get(pn).stream()
                    .filter(a -> a.contains(name + ".class"))
                    .findFirst();
        }
        return Optional.empty();
    }

    /**
     * Returns the list of Modules that can be found in the specified
     * module paths.
     */
    public Map<String, Module> getModules() {
        return nameToModule;
    }

    public Stream<Module> resolve(Set<String> roots) {
        if (roots.isEmpty()) {
            return nameToModule.values().stream();
        } else {
            return Configuration.empty()
                    .resolveRequires(finder, ModuleFinder.of(), roots)
                    .modules().stream()
                    .map(ResolvedModule::name)
                    .map(nameToModule::get);
        }
    }

    public List<Archive> classPathArchives() {
        return classpathArchives;
    }

    public List<Archive> initialArchives() {
        return initialArchives;
    }

    public Set<Module> rootModules() {
        return rootModules;
    }

    public Module toModule(ModuleReference mref) {
        try {
            String mn = mref.descriptor().name();
            URI location = mref.location().orElseThrow(FileNotFoundException::new);
            ModuleDescriptor md = mref.descriptor();
            Module.Builder builder = new Module.Builder(md, system.find(mn).isPresent());

            final ClassFileReader reader;
            if (location.getScheme().equals("jrt")) {
                reader = system.getClassReader(mn);
            } else {
                reader = ClassFileReader.newInstance(Paths.get(location), version);
            }

            builder.classes(reader);
            builder.location(location);

            return builder.build();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Runtime.Version getVersion() {
        return version;
    }

    /*
     * Close all archives e.g. JarFile
     */
    @Override
    public void close() throws IOException {
        for (Archive archive : initialArchives)
            archive.close();
        for (Archive archive : classpathArchives)
            archive.close();
        for (Module module : nameToModule.values())
            module.close();
    }

    static class SystemModuleFinder implements ModuleFinder {
        private static final String JAVA_HOME = System.getProperty("java.home");
        private static final String JAVA_SE = "java.se";

        private final FileSystem fileSystem;
        private final Path root;
        private final Map<String, ModuleReference> systemModules;

        SystemModuleFinder() {
            if (Files.isRegularFile(Paths.get(JAVA_HOME, "lib", "modules"))) {
                // jrt file system
                this.fileSystem = FileSystems.getFileSystem(URI.create("jrt:/"));
                this.root = fileSystem.getPath("/modules");
                this.systemModules = walk(root);
            } else {
                // exploded image
                this.fileSystem = FileSystems.getDefault();
                root = Paths.get(JAVA_HOME, "modules");
                this.systemModules = ModuleFinder.ofSystem().findAll().stream()
                    .collect(toMap(mref -> mref.descriptor().name(), Function.identity()));
            }
        }

        SystemModuleFinder(String javaHome) throws IOException {
            if (javaHome == null) {
                // --system none
                this.fileSystem = null;
                this.root = null;
                this.systemModules = Collections.emptyMap();
            } else {
                if (Files.isRegularFile(Paths.get(javaHome, "lib", "modules")))
                    throw new IllegalArgumentException("Invalid java.home: " + javaHome);

                // alternate java.home
                Map<String, String> env = new HashMap<>();
                env.put("java.home", javaHome);
                // a remote run-time image
                this.fileSystem = FileSystems.newFileSystem(URI.create("jrt:/"), env);
                this.root = fileSystem.getPath("/modules");
                this.systemModules = walk(root);
            }
        }

        private Map<String, ModuleReference> walk(Path root) {
            try (Stream<Path> stream = Files.walk(root, 1)) {
                return stream.filter(path -> !path.equals(root))
                             .map(this::toModuleReference)
                             .collect(toMap(mref -> mref.descriptor().name(),
                                            Function.identity()));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private ModuleReference toModuleReference(Path path) {
            Path minfo = path.resolve(MODULE_INFO);
            try (InputStream in = Files.newInputStream(minfo);
                 BufferedInputStream bin = new BufferedInputStream(in)) {

                ModuleDescriptor descriptor = dropHashes(ModuleDescriptor.read(bin));
                String mn = descriptor.name();
                URI uri = URI.create("jrt:/" + path.getFileName().toString());
                Supplier<ModuleReader> readerSupplier = new Supplier<>() {
                    @Override
                    public ModuleReader get() {
                        return new ModuleReader() {
                            @Override
                            public Optional<URI> find(String name) throws IOException {
                                return name.equals(mn)
                                    ? Optional.of(uri) : Optional.empty();
                            }

                            @Override
                            public void close() throws IOException {
                            }
                        };
                    }
                };

                return new ModuleReference(descriptor, uri, readerSupplier);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private ModuleDescriptor dropHashes(ModuleDescriptor md) {
            ModuleDescriptor.Builder builder = ModuleDescriptor.module(md.name());
            md.requires().forEach(builder::requires);
            md.exports().forEach(builder::exports);
            md.provides().values().stream().forEach(builder::provides);
            md.uses().stream().forEach(builder::uses);

            Set<String> concealed = new HashSet<>(md.packages());
            md.exports().stream().map(Exports::source).forEach(concealed::remove);
            concealed.forEach(builder::contains);

            return builder.build();
        }

        @Override
        public Set<ModuleReference> findAll() {
            return systemModules.values().stream().collect(toSet());
        }

        @Override
        public Optional<ModuleReference> find(String mn) {
            return systemModules.containsKey(mn)
                    ? Optional.of(systemModules.get(mn)) : Optional.empty();
        }

        public Stream<String> moduleNames() {
            return systemModules.values().stream()
                .map(mref -> mref.descriptor().name());
        }

        public ClassFileReader getClassReader(String modulename) throws IOException {
            Path mp = root.resolve(modulename);
            if (Files.exists(mp) && Files.isDirectory(mp)) {
                return ClassFileReader.newInstance(fileSystem, mp);
            } else {
                throw new FileNotFoundException(mp.toString());
            }
        }

        public Set<String> defaultSystemRoots() {
            Set<String> roots = new HashSet<>();
            boolean hasJava = false;
            if (systemModules.containsKey(JAVA_SE)) {
                // java.se is a system module
                hasJava = true;
                roots.add(JAVA_SE);
            }

            for (ModuleReference mref : systemModules.values()) {
                String mn = mref.descriptor().name();
                if (hasJava && mn.startsWith("java."))
                    continue;

                // add as root if observable and exports at least one package
                ModuleDescriptor descriptor = mref.descriptor();
                for (ModuleDescriptor.Exports e : descriptor.exports()) {
                    if (!e.isQualified()) {
                        roots.add(mn);
                        break;
                    }
                }
            }
            return roots;
        }
    }

    public static class Builder {

        final SystemModuleFinder systemModulePath;
        final Set<String> rootModules = new HashSet<>();
        final List<Archive> initialArchives = new ArrayList<>();
        final List<Path> paths = new ArrayList<>();
        final List<Path> classPaths = new ArrayList<>();

        ModuleFinder upgradeModulePath;
        ModuleFinder appModulePath;
        boolean addAllApplicationModules;
        boolean addAllDefaultModules;
        Runtime.Version version;

        public Builder() {
            this.systemModulePath = new SystemModuleFinder();
        }

        public Builder(String javaHome) throws IOException {
            this.systemModulePath = SystemModuleFinder.JAVA_HOME.equals(javaHome)
                ? new SystemModuleFinder()
                : new SystemModuleFinder(javaHome);
        }

        public Builder upgradeModulePath(String upgradeModulePath) {
            this.upgradeModulePath = createModulePathFinder(upgradeModulePath);
            return this;
        }

        public Builder appModulePath(String modulePath) {
            this.appModulePath = createModulePathFinder(modulePath);
            return this;
        }

        public Builder addmods(Set<String> addmods) {
            for (String mn : addmods) {
                switch (mn) {
                    case ALL_MODULE_PATH:
                        this.addAllApplicationModules = true;
                        break;
                    case ALL_DEFAULT:
                        this.addAllDefaultModules = true;
                        break;
                    default:
                        this.rootModules.add(mn);
                }
            }
            return this;
        }

        /*
         * This method is for --check option to find all target modules specified
         * in qualified exports.
         *
         * Include all system modules and modules found on modulepath
         */
        public Builder allModules() {
            systemModulePath.moduleNames()
                            .forEach(this.rootModules::add);
            this.addAllApplicationModules = true;
            return this;
        }

        public Builder multiRelease(Runtime.Version version) {
            this.version = version;
            return this;
        }

        public Builder addRoot(Path path) {
            Archive archive = Archive.getInstance(path, version);
            if (archive.contains(MODULE_INFO)) {
                paths.add(path);
            } else {
                initialArchives.add(archive);
            }
            return this;
        }

        public Builder addClassPath(String classPath) {
            this.classPaths.addAll(getClassPaths(classPath));
            return this;
        }

        public JdepsConfiguration build() throws  IOException {
            ModuleFinder finder = systemModulePath;
            if (upgradeModulePath != null) {
                finder = ModuleFinder.compose(upgradeModulePath, systemModulePath);
            }
            if (appModulePath != null) {
                finder = ModuleFinder.compose(finder, appModulePath);
            }
            if (!paths.isEmpty()) {
                ModuleFinder otherModulePath = ModuleFinder.of(paths.toArray(new Path[0]));

                finder = ModuleFinder.compose(finder, otherModulePath);
                // add modules specified on command-line (convenience) as root set
                otherModulePath.findAll().stream()
                        .map(mref -> mref.descriptor().name())
                        .forEach(rootModules::add);
            }
            if (addAllApplicationModules && appModulePath != null) {
                appModulePath.findAll().stream()
                    .map(mref -> mref.descriptor().name())
                    .forEach(rootModules::add);
            }

            return new JdepsConfiguration(systemModulePath,
                                          finder,
                                          rootModules,
                                          classPaths,
                                          initialArchives,
                                          addAllDefaultModules,
                                          version);
        }

        private static ModuleFinder createModulePathFinder(String mpaths) {
            if (mpaths == null) {
                return null;
            } else {
                String[] dirs = mpaths.split(File.pathSeparator);
                Path[] paths = new Path[dirs.length];
                int i = 0;
                for (String dir : dirs) {
                    paths[i++] = Paths.get(dir);
                }
                return ModuleFinder.of(paths);
            }
        }

        /*
         * Returns the list of Archive specified in cpaths and not included
         * initialArchives
         */
        private List<Path> getClassPaths(String cpaths) {
            if (cpaths.isEmpty()) {
                return Collections.emptyList();
            }
            List<Path> paths = new ArrayList<>();
            for (String p : cpaths.split(File.pathSeparator)) {
                if (p.length() > 0) {
                    // wildcard to parse all JAR files e.g. -classpath dir/*
                    int i = p.lastIndexOf(".*");
                    if (i > 0) {
                        Path dir = Paths.get(p.substring(0, i));
                        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.jar")) {
                            for (Path entry : stream) {
                                paths.add(entry);
                            }
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    } else {
                        paths.add(Paths.get(p));
                    }
                }
            }
            return paths;
        }
    }

}
