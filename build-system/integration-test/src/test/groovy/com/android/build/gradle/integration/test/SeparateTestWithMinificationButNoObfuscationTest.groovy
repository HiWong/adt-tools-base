/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.build.gradle.integration.test

import com.android.build.gradle.integration.common.category.DeviceTests
import com.android.build.gradle.integration.common.fixture.GradleTestProject
import com.android.build.gradle.integration.common.utils.AssumeUtil
import groovy.transform.CompileStatic
import org.junit.AfterClass
import org.junit.ClassRule
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Test for a separate test module that has minification turned on but no obfuscation
 * (no mapping.txt file produced)
 */
@CompileStatic
class SeparateTestWithMinificationButNoObfuscationTest {
    @ClassRule
    static public GradleTestProject project = GradleTestProject.builder()
            .fromTestProject("separateTestWithMinificationButNoObfuscation")
            .create()

    @Test
    void "test building"() {
        // just building fine is enough to test the regression.
        project.execute("clean", "assemble")
    }

    @AfterClass
    static void cleanUp() {
        project = null
    }

    @Test
    @Category(DeviceTests)
    void "connected check"() {
        project.execute(":test:deviceAndroidTest");
    }
}
