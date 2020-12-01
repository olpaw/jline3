/*
 * Copyright (c) 2020, 2020, Oracle and/or its affiliates. All rights reserved.
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

package com.oracle.svm.thirdparty.jline;

import com.oracle.svm.core.jdk.Resources;
import com.oracle.svm.core.jni.JNIRuntimeAccess;
import com.oracle.svm.util.ReflectionUtil;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeClassInitialization;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

public final class JLine3Feature implements Feature {

    public static final String TERMINAL_BUILDER = "org.graalvm.shadowed.org.jline.terminal.TerminalBuilder";
    private static final List<String> RESOURCES = Arrays.asList(
            "capabilities.txt",
            "colors.txt",
            "ansi.caps",
            "dumb.caps",
            "dumb-color.caps",
            "screen.caps",
            "screen-256color.caps",
            "windows.caps",
            "windows-256color.caps",
            "windows-conemu.caps",
            "windows-vtp.caps",
            "xterm.caps",
            "xterm-256color.caps");
    private static final String RESOURCE_PATH = "org/graalvm/shadowed/org/jline/utils/";
    /**
     * List of the classes that access the JNI library.
     */
    private static final List<String> JNI_CLASS_NAMES = Arrays.asList(
            "org.graalvm.shadowed.org.fusesource.jansi.internal.CLibrary",
            "org.graalvm.shadowed.org.fusesource.jansi.internal.CLibrary$WinSize",
            "org.graalvm.shadowed.org.fusesource.jansi.internal.CLibrary$Termios",
            "org.graalvm.shadowed.org.fusesource.jansi.internal.Kernel32",
            "org.graalvm.shadowed.org.fusesource.jansi.internal.Kernel32$SMALL_RECT",
            "org.graalvm.shadowed.org.fusesource.jansi.internal.Kernel32$COORD",
            "org.graalvm.shadowed.org.fusesource.jansi.internal.Kernel32$CONSOLE_SCREEN_BUFFER_INFO",
            "org.graalvm.shadowed.org.fusesource.jansi.internal.Kernel32$CHAR_INFO",
            "org.graalvm.shadowed.org.fusesource.jansi.internal.Kernel32$KEY_EVENT_RECORD",
            "org.graalvm.shadowed.org.fusesource.jansi.internal.Kernel32$MOUSE_EVENT_RECORD",
            "org.graalvm.shadowed.org.fusesource.jansi.internal.Kernel32$WINDOW_BUFFER_SIZE_RECORD",
            "org.graalvm.shadowed.org.fusesource.jansi.internal.Kernel32$FOCUS_EVENT_RECORD",
            "org.graalvm.shadowed.org.fusesource.jansi.internal.Kernel32$MENU_EVENT_RECORD",
            "org.graalvm.shadowed.org.fusesource.jansi.internal.Kernel32$INPUT_RECORD");
    /**
     * Other classes that need to be initialized at run time because they reference the JNI classes
     * and/or have static state that depends on run time state.
     */
    private static final List<String> RUNTIME_INIT_CLASS_NAMES = Arrays.asList(
            "org.graalvm.shadowed.org.fusesource.jansi.AnsiConsole",
            "org.graalvm.shadowed.org.fusesource.jansi.WindowsAnsiOutputStream",
            "org.graalvm.shadowed.org.fusesource.jansi.WindowsAnsiProcessor",
            "org.graalvm.shadowed.org.jline.terminal.impl.jna.win.JnaWinSysTerminal",
            "org.graalvm.shadowed.org.jline.terminal.impl.jansi.win.WindowsAnsiWriter",
            "org.graalvm.shadowed.org.jline.terminal.impl.jansi.win.JansiWinConsoleWriter",
            "org.graalvm.shadowed.org.fusesource.jansi.internal.CLibrary",
            "org.graalvm.shadowed.org.fusesource.jansi.internal.CLibrary$Termios",
            "org.graalvm.shadowed.org.fusesource.jansi.internal.CLibrary$WinSize");
    private AtomicBoolean resourceRegistered = new AtomicBoolean();

    @Override
    public boolean isInConfiguration(IsInConfigurationAccess access) {
        return access.findClassByName(TERMINAL_BUILDER) != null;
    }

    @Override
    public void duringSetup(DuringSetupAccess access) {
        Stream.concat(JNI_CLASS_NAMES.stream(), RUNTIME_INIT_CLASS_NAMES.stream())
                .map(access::findClassByName)
                .filter(Objects::nonNull)
                .forEach(RuntimeClassInitialization::initializeAtRunTime);
    }

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        /*
         * Each listed class has a native method named "init" that initializes all declared
         * fields of the class using JNI. So when the "init" method gets reachable (which means
         * the class initializer got reachable), we need to register all fields for JNI access.
         */
        JNI_CLASS_NAMES.stream()
                .map(access::findClassByName)
                .filter(Objects::nonNull)
                .map(jniClass -> ReflectionUtil.lookupMethod(jniClass, "init"))
                .forEach(initMethod -> access.registerReachabilityHandler(a -> registerJNIFields(initMethod), initMethod));
        for (String resource : RESOURCES) {
            String resourcePath = RESOURCE_PATH + resource;
            final InputStream resourceAsStream = ClassLoader.getSystemResourceAsStream(resourcePath);
            Resources.registerResource(resourcePath, resourceAsStream);
        }
    }

    private void registerJNIFields(Method initMethod) {
        Class<?> jniClass = initMethod.getDeclaringClass();
        JNIRuntimeAccess.register(jniClass.getDeclaredFields());

        if (!resourceRegistered.getAndSet(true)) {
            /* The native library that is included as a resource in the .jar file. */
            String resource = "META-INF/native/windows64/jansi.dll";
            Resources.registerResource(resource, jniClass.getClassLoader().getResourceAsStream(resource));
        }
    }
}
