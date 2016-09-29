/*
 * Copyright (c) 2016, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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

/**
 * @test
 * @summary Test --add-modules and --limit-modules; also test the "enabled" modules.
 * @library /tools/lib
 * @modules jdk.compiler/com.sun.tools.javac.api
 *          jdk.compiler/com.sun.tools.javac.main
 *          jdk.jdeps/com.sun.tools.classfile
 * @build toolbox.ToolBox toolbox.JarTask toolbox.JavacTask toolbox.JavaTask ModuleTestBase
 * @run main AnnotationsOnModules
 */

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.ModuleElement;
import javax.lang.model.element.TypeElement;

import com.sun.tools.classfile.Attribute;
import com.sun.tools.classfile.ClassFile;
import com.sun.tools.classfile.RuntimeInvisibleAnnotations_attribute;
import com.sun.tools.classfile.RuntimeVisibleAnnotations_attribute;
import toolbox.JavacTask;
import toolbox.Task.OutputKind;

public class AnnotationsOnModules extends ModuleTestBase {

    public static void main(String... args) throws Exception {
        AnnotationsOnModules t = new AnnotationsOnModules();
        t.runTests();
    }

    @Test
    public void testSimpleAnnotation(Path base) throws Exception {
        Path moduleSrc = base.resolve("module-src");
        Path m1 = moduleSrc.resolve("m1");

        tb.writeJavaFiles(m1,
                          "@Deprecated module m1 { }");

        Path modulePath = base.resolve("module-path");

        Files.createDirectories(modulePath);

        new JavacTask(tb)
                .options("--module-source-path", moduleSrc.toString())
                .outdir(modulePath)
                .files(findJavaFiles(m1))
                .run()
                .writeAll();

        ClassFile cf = ClassFile.read(modulePath.resolve("m1").resolve("module-info.class"));
        RuntimeVisibleAnnotations_attribute annotations = (RuntimeVisibleAnnotations_attribute) cf.attributes.map.get(Attribute.RuntimeVisibleAnnotations);

        if (annotations == null || annotations.annotations.length != 1) {
            throw new AssertionError("Annotations not correct!");
        }
    }

    @Test
    public void testAnnotationWithImport(Path base) throws Exception {
        Path moduleSrc = base.resolve("module-src");
        Path m1 = moduleSrc.resolve("m1");

        tb.writeJavaFiles(m1,
                          "import m1.A; @A module m1 { }",
                          "package m1; import java.lang.annotation.*; @Target(ElementType.MODULE) public @interface A {}");

        Path modulePath = base.resolve("module-path");

        Files.createDirectories(modulePath);

        new JavacTask(tb)
                .options("--module-source-path", moduleSrc.toString())
                .outdir(modulePath)
                .files(findJavaFiles(m1))
                .run()
                .writeAll();

        ClassFile cf = ClassFile.read(modulePath.resolve("m1").resolve("module-info.class"));
        RuntimeInvisibleAnnotations_attribute annotations = (RuntimeInvisibleAnnotations_attribute) cf.attributes.map.get(Attribute.RuntimeInvisibleAnnotations);

        if (annotations == null || annotations.annotations.length != 1) {
            throw new AssertionError("Annotations not correct!");
        }
    }

    @Test
    public void testModuleInfoAnnotationsInAPI(Path base) throws Exception {
        Path moduleSrc = base.resolve("module-src");
        Path m1 = moduleSrc.resolve("m1");

        tb.writeJavaFiles(m1,
                          "import m1.*; @A @Deprecated @E @E module m1 { }",
                          "package m1; import java.lang.annotation.*; @Target(ElementType.MODULE) public @interface A {}",
                          "package m1; import java.lang.annotation.*; @Target(ElementType.MODULE) @Repeatable(C.class) public @interface E {}",
                          "package m1; import java.lang.annotation.*; @Target(ElementType.MODULE) public @interface C { public E[] value(); }");

        Path modulePath = base.resolve("module-path");

        Files.createDirectories(modulePath);

        new JavacTask(tb)
                .options("--module-source-path", moduleSrc.toString(),
                         "-processor", AP.class.getName())
                .outdir(modulePath)
                .files(findJavaFiles(m1))
                .run()
                .writeAll();

        Path src = base.resolve("src");

        tb.writeJavaFiles(src,
                          "class T {}");

        Path out = base.resolve("out");

        Files.createDirectories(out);

        new JavacTask(tb)
                .options("--module-path", modulePath.toString(),
                         "--add-modules", "m1",
                         "-processor", AP.class.getName())
                .outdir(out)
                .files(findJavaFiles(src))
                .run()
                .writeAll();

        new JavacTask(tb)
                .options("--module-path", modulePath.toString() + File.pathSeparator + out.toString(),
                         "--add-modules", "m1",
                         "-processor", AP.class.getName(),
                         "-proc:only")
                .classes("m1/m1.A")
                .files(findJavaFiles(src))
                .run()
                .writeAll();
    }

    @SupportedAnnotationTypes("*")
    public static final class AP extends AbstractProcessor {

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
            ModuleElement m1 = processingEnv.getElementUtils().getModuleElement("m1");
            Set<String> actualAnnotations = new HashSet<>();
            Set<String> expectedAnnotations =
                    new HashSet<>(Arrays.asList("@m1.A", "@java.lang.Deprecated", "@m1.C({@m1.E, @m1.E})"));

            for (AnnotationMirror am : m1.getAnnotationMirrors()) {
                actualAnnotations.add(am.toString());
            }

            if (!expectedAnnotations.equals(actualAnnotations)) {
                throw new AssertionError("Incorrect annotations: " + actualAnnotations);
            }

            return false;
        }

    }

    @Test
    public void testModuleDeprecation(Path base) throws Exception {
        Path moduleSrc = base.resolve("module-src");
        Path m1 = moduleSrc.resolve("m1");

        tb.writeJavaFiles(m1,
                          "@Deprecated module m1 { }");

        Path m2 = moduleSrc.resolve("m2");

        tb.writeJavaFiles(m2,
                          "@Deprecated module m2 { }");

        Path m3 = moduleSrc.resolve("m3");

        Path modulePath = base.resolve("module-path");

        Files.createDirectories(modulePath);

        List<String> actual;
        List<String> expected;

        for (String suppress : new String[] {"", "@Deprecated ", "@SuppressWarnings(\"deprecation\") "}) {
            tb.writeJavaFiles(m3,
                              suppress + "module m3 {\n" +
                              "    requires m1;\n" +
                              "    exports api to m1, m2;\n" +
                              "}",
                              "package api; public class Api { }");
            actual = new JavacTask(tb)
                    .options("--module-source-path", moduleSrc.toString(),
                             "-XDrawDiagnostics")
                    .outdir(modulePath)
                    .files(findJavaFiles(moduleSrc))
                    .run()
                    .writeAll()
                    .getOutputLines(OutputKind.DIRECT);

            if (suppress.isEmpty()) {
                expected = Arrays.asList(
                        "- compiler.note.deprecated.filename: module-info.java",
                        "- compiler.note.deprecated.recompile");
            } else {
                expected = Arrays.asList("");
            }

            if (!expected.equals(actual)) {
                throw new AssertionError("Unexpected output: " + actual + "; suppress: " + suppress);
            }

            actual = new JavacTask(tb)
                    .options("--module-source-path", moduleSrc.toString(),
                             "-XDrawDiagnostics",
                             "-Xlint:deprecation")
                    .outdir(modulePath)
                    .files(findJavaFiles(moduleSrc))
                    .run()
                    .writeAll()
                    .getOutputLines(OutputKind.DIRECT);

            if (suppress.isEmpty()) {
                expected = Arrays.asList(
                        "module-info.java:2:14: compiler.warn.has.been.deprecated.module: m1",
                        "module-info.java:3:20: compiler.warn.has.been.deprecated.module: m1",
                        "module-info.java:3:24: compiler.warn.has.been.deprecated.module: m2",
                        "3 warnings");
            } else {
                expected = Arrays.asList("");
            }

            if (!expected.equals(actual)) {
                throw new AssertionError("Unexpected output: " + actual + "; suppress: " + suppress);
            }

            //load the deprecated module-infos from classfile:
            actual = new JavacTask(tb)
                    .options("--module-path", modulePath.toString(),
                             "-XDrawDiagnostics",
                             "-Xlint:deprecation")
                    .outdir(modulePath.resolve("m3"))
                    .files(findJavaFiles(moduleSrc.resolve("m3")))
                    .run()
                    .writeAll()
                    .getOutputLines(OutputKind.DIRECT);

            if (!expected.equals(actual)) {
                throw new AssertionError("Unexpected output: " + actual + "; suppress: " + suppress);
            }
        }
    }

}
