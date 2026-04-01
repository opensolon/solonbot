/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.codecli.portal.ui.theme;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Portal UI 内置主题表。
 */
public final class PortalThemes {
    private static final List<PortalTheme> BUILT_INS;
    private static final PortalTheme DEFAULT_THEME;

    static {
        List<PortalTheme> themes = new ArrayList<PortalTheme>();
        themes.add(new PortalTheme(
                "solon",
                PortalColor.rgb(255, 125, 144),
                PortalColor.rgb(255, 154, 168),
                PortalColor.rgb(243, 245, 247),
                PortalColor.rgb(114, 123, 137),
                PortalColor.rgb(160, 168, 184),
                PortalColor.rgb(39, 201, 63),
                PortalColor.rgb(232, 194, 122),
                PortalColor.rgb(244, 124, 124),
                PortalColor.rgb(90, 96, 110),
                PortalColor.rgb(243, 245, 247),
                PortalColor.rgb(255, 154, 168),
                PortalColor.rgb(160, 168, 184),
                PortalColor.rgb(255, 125, 144),
                PortalColor.rgb(114, 123, 137),
                PortalColor.rgb(114, 123, 137),
                PortalColor.rgb(160, 168, 184),
                PortalColor.rgb(243, 245, 247),
                PortalColor.rgb(231, 241, 250),
                PortalColor.rgb(114, 123, 137),
                PortalColor.rgb(255, 125, 144),
                PortalColor.rgb(243, 245, 247),
                PortalColor.rgb(232, 194, 122),
                PortalColor.rgb(165, 214, 132),
                PortalColor.rgb(60, 65, 75),
                PortalColor.rgb(255, 125, 144),
                PortalColor.rgb(130, 170, 255),
                PortalColor.rgb(114, 123, 137),
                PortalColor.rgb(60, 65, 75),
                PortalColor.rgb(80, 90, 110),
                PortalColor.rgb(200, 210, 220)
        ));
        themes.add(new PortalTheme(
                "opencode",
                PortalColor.rgb(14, 165, 233),
                PortalColor.rgb(56, 189, 248),
                PortalColor.rgb(239, 246, 255),
                PortalColor.rgb(148, 163, 184),
                PortalColor.rgb(125, 211, 252),
                PortalColor.rgb(34, 197, 94),
                PortalColor.rgb(245, 158, 11),
                PortalColor.rgb(248, 113, 113),
                PortalColor.rgb(44, 77, 122),
                PortalColor.rgb(239, 246, 255),
                PortalColor.rgb(56, 189, 248),
                PortalColor.rgb(125, 211, 252),
                PortalColor.rgb(56, 189, 248),
                PortalColor.rgb(148, 163, 184),
                PortalColor.rgb(148, 163, 184),
                PortalColor.rgb(125, 211, 252),
                PortalColor.rgb(239, 246, 255),
                PortalColor.rgb(191, 219, 254),
                PortalColor.rgb(148, 163, 184),
                PortalColor.rgb(56, 189, 248),
                PortalColor.rgb(239, 246, 255),
                PortalColor.rgb(251, 191, 36),
                PortalColor.rgb(110, 231, 183),
                PortalColor.rgb(44, 77, 122),
                PortalColor.rgb(56, 189, 248),
                PortalColor.rgb(147, 197, 253),
                PortalColor.rgb(148, 163, 184),
                PortalColor.rgb(44, 77, 122),
                PortalColor.rgb(70, 100, 145),
                PortalColor.rgb(239, 246, 255)
        ));
        themes.add(new PortalTheme(
                "ocean",
                PortalColor.rgb(96, 165, 250),
                PortalColor.rgb(56, 189, 248),
                PortalColor.rgb(232, 241, 255),
                PortalColor.rgb(148, 163, 184),
                PortalColor.rgb(125, 211, 252),
                PortalColor.rgb(74, 222, 128),
                PortalColor.rgb(250, 204, 21),
                PortalColor.rgb(248, 113, 113),
                PortalColor.rgb(44, 69, 99),
                PortalColor.rgb(232, 241, 255),
                PortalColor.rgb(56, 189, 248),
                PortalColor.rgb(125, 211, 252),
                PortalColor.rgb(96, 165, 250),
                PortalColor.rgb(148, 163, 184),
                PortalColor.rgb(148, 163, 184),
                PortalColor.rgb(125, 211, 252),
                PortalColor.rgb(232, 241, 255),
                PortalColor.rgb(191, 219, 254),
                PortalColor.rgb(148, 163, 184),
                PortalColor.rgb(96, 165, 250),
                PortalColor.rgb(232, 241, 255),
                PortalColor.rgb(250, 204, 21),
                PortalColor.rgb(134, 239, 172),
                PortalColor.rgb(44, 69, 99),
                PortalColor.rgb(125, 211, 252),
                PortalColor.rgb(147, 197, 253),
                PortalColor.rgb(148, 163, 184),
                PortalColor.rgb(44, 69, 99),
                PortalColor.rgb(80, 100, 130),
                PortalColor.rgb(200, 220, 245)
        ));
        themes.add(new PortalTheme(
                "forest",
                PortalColor.rgb(74, 222, 128),
                PortalColor.rgb(34, 197, 94),
                PortalColor.rgb(236, 253, 245),
                PortalColor.rgb(134, 164, 148),
                PortalColor.rgb(110, 231, 183),
                PortalColor.rgb(74, 222, 128),
                PortalColor.rgb(250, 204, 21),
                PortalColor.rgb(248, 113, 113),
                PortalColor.rgb(54, 94, 75),
                PortalColor.rgb(236, 253, 245),
                PortalColor.rgb(74, 222, 128),
                PortalColor.rgb(110, 231, 183),
                PortalColor.rgb(74, 222, 128),
                PortalColor.rgb(134, 164, 148),
                PortalColor.rgb(134, 164, 148),
                PortalColor.rgb(110, 231, 183),
                PortalColor.rgb(236, 253, 245),
                PortalColor.rgb(209, 250, 229),
                PortalColor.rgb(134, 164, 148),
                PortalColor.rgb(74, 222, 128),
                PortalColor.rgb(236, 253, 245),
                PortalColor.rgb(250, 204, 21),
                PortalColor.rgb(187, 247, 208),
                PortalColor.rgb(54, 94, 75),
                PortalColor.rgb(74, 222, 128),
                PortalColor.rgb(110, 231, 183),
                PortalColor.rgb(134, 164, 148),
                PortalColor.rgb(54, 94, 75),
                PortalColor.rgb(80, 110, 90),
                PortalColor.rgb(210, 240, 220)
        ));
        themes.add(new PortalTheme(
                "graphite",
                PortalColor.rgb(148, 163, 184),
                PortalColor.rgb(226, 232, 240),
                PortalColor.rgb(248, 250, 252),
                PortalColor.rgb(148, 163, 184),
                PortalColor.rgb(196, 181, 253),
                PortalColor.rgb(74, 222, 128),
                PortalColor.rgb(251, 191, 36),
                PortalColor.rgb(248, 113, 113),
                PortalColor.rgb(71, 85, 105),
                PortalColor.rgb(248, 250, 252),
                PortalColor.rgb(226, 232, 240),
                PortalColor.rgb(148, 163, 184),
                PortalColor.rgb(226, 232, 240),
                PortalColor.rgb(148, 163, 184),
                PortalColor.rgb(148, 163, 184),
                PortalColor.rgb(148, 163, 184),
                PortalColor.rgb(241, 245, 249),
                PortalColor.rgb(248, 250, 252),
                PortalColor.rgb(203, 213, 225),
                PortalColor.rgb(226, 232, 240),
                PortalColor.rgb(248, 250, 252),
                PortalColor.rgb(250, 204, 21),
                PortalColor.rgb(196, 181, 253),
                PortalColor.rgb(71, 85, 105),
                PortalColor.rgb(226, 232, 240),
                PortalColor.rgb(148, 163, 184),
                PortalColor.rgb(148, 163, 184),
                PortalColor.rgb(71, 85, 105),
                PortalColor.rgb(100, 116, 139),
                PortalColor.rgb(200, 210, 225)
        ));
        themes.add(new PortalTheme(
                "sakura",
                PortalColor.rgb(244, 114, 182),
                PortalColor.rgb(236, 72, 153),
                PortalColor.rgb(255, 241, 242),
                PortalColor.rgb(190, 152, 165),
                PortalColor.rgb(236, 161, 190),
                PortalColor.rgb(74, 222, 128),
                PortalColor.rgb(251, 191, 36),
                PortalColor.rgb(248, 113, 113),
                PortalColor.rgb(133, 78, 107),
                PortalColor.rgb(255, 241, 242),
                PortalColor.rgb(244, 114, 182),
                PortalColor.rgb(236, 161, 190),
                PortalColor.rgb(244, 114, 182),
                PortalColor.rgb(190, 152, 165),
                PortalColor.rgb(190, 152, 165),
                PortalColor.rgb(236, 161, 190),
                PortalColor.rgb(252, 231, 243),
                PortalColor.rgb(255, 241, 242),
                PortalColor.rgb(251, 207, 232),
                PortalColor.rgb(244, 114, 182),
                PortalColor.rgb(255, 241, 242),
                PortalColor.rgb(251, 191, 36),
                PortalColor.rgb(253, 186, 116),
                PortalColor.rgb(133, 78, 107),
                PortalColor.rgb(244, 114, 182),
                PortalColor.rgb(236, 161, 190),
                PortalColor.rgb(190, 152, 165),
                PortalColor.rgb(133, 78, 107),
                PortalColor.rgb(160, 110, 135),
                PortalColor.rgb(245, 220, 230)
        ));

        BUILT_INS = Collections.unmodifiableList(themes);
        DEFAULT_THEME = BUILT_INS.get(0);
    }

    private PortalThemes() {
    }

    public static PortalTheme defaultTheme() {
        return DEFAULT_THEME;
    }

    public static List<PortalTheme> builtIns() {
        return BUILT_INS;
    }

    public static List<String> names() {
        List<String> names = new ArrayList<String>();
        for (PortalTheme theme : BUILT_INS) {
            names.add(theme.name());
        }
        return Collections.unmodifiableList(names);
    }

    public static PortalTheme find(String name) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }

        String normalized = name.trim().toLowerCase(Locale.ROOT);
        for (PortalTheme theme : BUILT_INS) {
            if (theme.name().equalsIgnoreCase(normalized)) {
                return theme;
            }
        }
        return null;
    }
}
