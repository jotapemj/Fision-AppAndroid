package com.jotapeveloper.fision;

import java.util.ArrayList;
import java.util.List;

/** Static catalog of every third-party dependency shipped with the app.
 *  Add a new entry here when a new library is integrated. */
public class LicenseCatalog {

    // ---- License full-text constants (canonical texts) ------------------

    private static final String LIC_APACHE_2_0 =
            "                                 Apache License\n" +
            "                           Version 2.0, January 2004\n" +
            "                        http://www.apache.org/licenses/\n\n" +
            "   Licensed under the Apache License, Version 2.0 (the \"License\");\n" +
            "   you may not use this file except in compliance with the License.\n" +
            "   You may obtain a copy of the License at\n\n" +
            "       http://www.apache.org/licenses/LICENSE-2.0\n\n" +
            "   Unless required by applicable law or agreed to in writing, software\n" +
            "   distributed under the License is distributed on an \"AS IS\" BASIS,\n" +
            "   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.\n" +
            "   See the License for the specific language governing permissions and\n" +
            "   limitations under the License.\n\n" +
            "   TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION\n\n" +
            "   1. Definitions. \"License\" means the terms and conditions for use,\n" +
            "      reproduction, and distribution as defined by Sections 1 through 9 of\n" +
            "      this document.\n\n" +
            "   2. Grant of Copyright License. Subject to the terms and conditions of\n" +
            "      this License, each Contributor hereby grants to You a perpetual,\n" +
            "      worldwide, non-exclusive, no-charge, royalty-free, irrevocable\n" +
            "      copyright license to reproduce, prepare Derivative Works of,\n" +
            "      publicly display, publicly perform, sublicense, and distribute the\n" +
            "      Work and such Derivative Works in Source or Object form.\n\n" +
            "   3. Grant of Patent License. Subject to the terms and conditions of\n" +
            "      this License, each Contributor hereby grants to You a perpetual,\n" +
            "      worldwide, non-exclusive, no-charge, royalty-free, irrevocable\n" +
            "      (except as stated in this section) patent license to make, have\n" +
            "      made, use, offer to sell, sell, import, and otherwise transfer the\n" +
            "      Work, where such license applies only to those patent claims\n" +
            "      licensable by such Contributor that are necessarily infringed by\n" +
            "      their Contribution(s) alone or by combination of their\n" +
            "      Contribution(s) with the Work to which such Contribution(s) was\n" +
            "      submitted.\n\n" +
            "   For the full text visit http://www.apache.org/licenses/LICENSE-2.0";

    private static final String LIC_MIT =
            "MIT License\n\n" +
            "Permission is hereby granted, free of charge, to any person obtaining a copy\n" +
            "of this software and associated documentation files (the \"Software\"), to deal\n" +
            "in the Software without restriction, including without limitation the rights\n" +
            "to use, copy, modify, merge, publish, distribute, sublicense, and/or sell\n" +
            "copies of the Software, and to permit persons to whom the Software is\n" +
            "furnished to do so, subject to the following conditions:\n\n" +
            "The above copyright notice and this permission notice shall be included in all\n" +
            "copies or substantial portions of the Software.\n\n" +
            "THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR\n" +
            "IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,\n" +
            "FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE\n" +
            "AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER\n" +
            "LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,\n" +
            "OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE\n" +
            "SOFTWARE.";

    private static final String LIC_LGPL_2_1 =
            "GNU LESSER GENERAL PUBLIC LICENSE\nVersion 2.1, February 1999\n\n" +
            "Copyright (C) 1991, 1999 Free Software Foundation, Inc.\n" +
            "Everyone is permitted to copy and distribute verbatim copies of this\n" +
            "license document, but changing it is not allowed.\n\n" +
            "This version of the GNU Lesser General Public License incorporates the\n" +
            "terms and conditions of version 3 of the GNU General Public License,\n" +
            "supplemented by the additional permissions listed below.\n\n" +
            "The Lesser General Public License is intended to guarantee your freedom\n" +
            "to share and change all versions of a program — to make sure it remains\n" +
            "free software for all its users. This license, the Lesser General Public\n" +
            "License, applies to some specially designated software packages —\n" +
            "typically libraries — of the Free Software Foundation and other authors\n" +
            "who decide to use it.\n\n" +
            "For the full text visit https://www.gnu.org/licenses/lgpl-2.1.html";

    private static final String LIC_PUBLIC_DOMAIN =
            "This is free and unencumbered software released into the public domain.\n\n" +
            "Anyone is free to copy, modify, publish, use, compile, sell, or distribute\n" +
            "this software, either in source code form or as a compiled binary, for any\n" +
            "purpose, commercial or non-commercial, and by any means.\n\n" +
            "In jurisdictions that recognize copyright laws, the author or authors of\n" +
            "this software dedicate any and all copyright interest in the software to\n" +
            "the public domain. We make this dedication for the benefit of the public\n" +
            "at large and to the detriment of our heirs and successors.\n\n" +
            "THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR\n" +
            "IMPLIED. IN NO EVENT SHALL THE AUTHORS BE HELD LIABLE FOR ANY CLAIM,\n" +
            "DAMAGES OR OTHER LIABILITY ARISING FROM, OUT OF OR IN CONNECTION WITH THE\n" +
            "SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.\n\n" +
            "For more information, please refer to <https://unlicense.org>";

    private static final String LIC_KEMAR =
            "KEMAR HRTF Measurements\n" +
            "Bill Gardner and Keith Martin, MIT Media Lab, 1995\n\n" +
            "This data is Copyright 1994 by the MIT Media Laboratory. It is\n" +
            "provided free with no restrictions on use, provided the authors are\n" +
            "cited when the data is used in any research or commercial\n" +
            "application:\n\n" +
            "Gardner, W. G., and Martin, K. D. (1995). \"HRTF measurements of a\n" +
            "KEMAR.\" Journal of the Acoustical Society of America, 97(6),\n" +
            "3907-3908.\n\n" +
            "Original dataset available at:\n" +
            "https://sound.media.mit.edu/resources/KEMAR.html";

    private static final String LIC_OFL =
            "This Font Software is licensed under the SIL Open Font License,\n" +
            "Version 1.1. The font may be used, studied, modified and\n" +
            "redistributed freely, embedded in software, as long as it is not\n" +
            "sold by itself and the copyright notice and license are kept.\n" +
            "The license text travels inside the font file and in licenses/\n" +
            "of the source repository.\n\n" +
            "Full text and FAQ: https://openfontlicense.org";

    // ---- Entries ---------------------------------------------------------
    private static final List<LicenseEntry> ENTRIES = new ArrayList<>();
    static {
        ENTRIES.add(new LicenseEntry(
                "Oboe", "Google",
                "Apache 2.0", "https://github.com/google/oboe",
                LIC_APACHE_2_0));
        ENTRIES.add(new LicenseEntry(
                "SoundTouch", "Olli Parviainen",
                "LGPL 2.1", "https://www.surina.net/soundtouch/",
                LIC_LGPL_2_1));
        ENTRIES.add(new LicenseEntry(
                "dr_flac", "David Reid (mackron)",
                "MIT / Public Domain", "https://github.com/mackron/dr_libs",
                LIC_MIT + "\n\n---\n\n" + LIC_PUBLIC_DOMAIN));
        ENTRIES.add(new LicenseEntry(
                "chords-db", "David Rubert",
                "MIT", "https://github.com/tombatossals/chords-db",
                "Copyright (c) 2016 David Rubert\n\n" + LIC_MIT));
        ENTRIES.add(new LicenseEntry(
                "KEMAR HRTF dataset", "Bill Gardner, Keith Martin — MIT Media Lab",
                "Free use with attribution","https://sound.media.mit.edu/resources/KEMAR.html",
                LIC_KEMAR));
        ENTRIES.add(new LicenseEntry(
                "Downbeat click sounds", "Ludwig Peter Müller",
                "CC0 1.0", "https://creativecommons.org/publicdomain/zero/1.0/",
                "Metronome sounds recorded by Ludwig Peter Müller in December 2020.\n" +
                "Free to use under the license Creative Commons CC0 1.0 Universal."));
        ENTRIES.add(new LicenseEntry(
                "Material Components", "Google",
                "Apache 2.0", "https://github.com/material-components/material-components-android",
                LIC_APACHE_2_0));
        ENTRIES.add(new LicenseEntry(
                "AndroidX / Room / Biometric / Preference", "Google",
                "Apache 2.0", "https://developer.android.com/jetpack/androidx",
                LIC_APACHE_2_0));
        ENTRIES.add(new LicenseEntry(
                "OkHttp", "Square",
                "Apache 2.0", "https://square.github.io/okhttp/",
                LIC_APACHE_2_0));
        ENTRIES.add(new LicenseEntry(
                "Retrofit", "Square",
                "Apache 2.0", "https://square.github.io/retrofit/",
                LIC_APACHE_2_0));
        ENTRIES.add(new LicenseEntry(
                "Gson", "Google",
                "Apache 2.0", "https://github.com/google/gson",
                LIC_APACHE_2_0));
        ENTRIES.add(new LicenseEntry(
                "3D head model (Audio 3D)", "Meshy",
                "CC BY 4.0", "https://creativecommons.org/licenses/by/4.0/",
                "The listener head model used in the 3D audio scene was generated\n" +
                "with Meshy (https://www.meshy.ai) and is licensed under the Creative\n" +
                "Commons Attribution 4.0 International License (CC BY 4.0)."));
        ENTRIES.add(new LicenseEntry(
                "Airplane Mode (demo song)", "Josh Woodward",
                "CC BY 4.0", "https://www.joshwoodward.com",
                "\"Airplane Mode\" by Josh Woodward, from the album \"The Beautiful\n" +
                "Machine\", with its cover. FisionServer adds it to every new account\n" +
                "as a demo song; it can be deleted like any other song.\n\n" +
                "Licensed under the Creative Commons Attribution 4.0 International\n" +
                "License (CC BY 4.0): https://creativecommons.org/licenses/by/4.0/\n\n" +
                "The stems, chords, beats and lyrics are derived from the original\n" +
                "recording by FisionServer."));
        ENTRIES.add(new LicenseEntry(
                "Noto Sans", "The Noto Project Authors",
                "SIL OFL 1.1", "https://github.com/notofonts/latin-greek-cyrillic",
                "Copyright 2022 The Noto Project Authors\n\n" + LIC_OFL));
        ENTRIES.add(new LicenseEntry(
                "MusGlyphs", "Dan Kreider",
                "SIL OFL 1.1", "https://www.notationcentral.com/product/musglyphs/",
                "Copyright (c) Dan Kreider, 2021\n\n" + LIC_OFL));
    }

    public static List<LicenseEntry> all() { return ENTRIES; }
}
