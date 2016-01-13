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

package com.android.build.gradle.internal.incremental;

import static com.google.common.truth.Truth.assertThat;

import com.android.build.gradle.internal.incremental.InstantRunBuildContext.Build;
import com.android.sdklib.AndroidVersion;
import com.android.utils.XmlUtils;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.io.Files;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import java.io.File;
import java.io.IOException;
import java.util.List;

import javax.xml.parsers.ParserConfigurationException;

/**
 * Tests for the {@link InstantRunBuildContext}
 */
public class InstantRunBuildContextTest {

    @Test
    public void testTaskDurationRecording() throws ParserConfigurationException {
        InstantRunBuildContext instantRunBuildContext = new InstantRunBuildContext();
        instantRunBuildContext.startRecording(InstantRunBuildContext.TaskType.VERIFIER);
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        assertThat(instantRunBuildContext.stopRecording(InstantRunBuildContext.TaskType.VERIFIER))
                .isAtLeast(10L);
        assertThat(instantRunBuildContext.getBuildId()).isNotEqualTo(
                new InstantRunBuildContext().getBuildId());
    }

    @Test
    public void testPersistenceFromCleanState() throws ParserConfigurationException {
        InstantRunBuildContext instantRunBuildContext = new InstantRunBuildContext();
        String persistedState = instantRunBuildContext.toXml();
        assertThat(persistedState).isNotEmpty();
        assertThat(persistedState).contains(InstantRunBuildContext.ATTR_TIMESTAMP);
    }

    @Test
    public void testFormatPresence() throws ParserConfigurationException {
        InstantRunBuildContext instantRunBuildContext = new InstantRunBuildContext();
        String persistedState = instantRunBuildContext.toXml();
        assertThat(persistedState).isNotEmpty();
        assertThat(persistedState).contains(InstantRunBuildContext.ATTR_FORMAT
                + "=\"" + InstantRunBuildContext.CURRENT_FORMAT + "\"");
    }

    @Test
    public void testLoadingFromCleanState()
            throws ParserConfigurationException, SAXException, IOException {
        InstantRunBuildContext instantRunBuildContext = new InstantRunBuildContext();
        File file = new File("/path/to/non/existing/file");
        instantRunBuildContext.loadFromXmlFile(file);
        assertThat(instantRunBuildContext.getBuildId()).isAtLeast(1L);
    }

    @Test
    public void testLoadingFromPreviousState()
            throws IOException, ParserConfigurationException, SAXException {
        File tmpFile = createMarkedBuildInfo();

        InstantRunBuildContext newContext = new InstantRunBuildContext();
        newContext.loadFromXmlFile(tmpFile);
        String xml = newContext.toXml();
        assertThat(xml).contains(InstantRunBuildContext.ATTR_TIMESTAMP);
    }

    @Test
    public void testPersistingAndLoadingPastBuilds()
            throws IOException, ParserConfigurationException, SAXException {
        InstantRunBuildContext instantRunBuildContext = new InstantRunBuildContext();
        File buildInfo = createBuildInfo(instantRunBuildContext);
        instantRunBuildContext = new InstantRunBuildContext();
        instantRunBuildContext.loadFromXmlFile(buildInfo);
        assertThat(instantRunBuildContext.getPreviousBuilds()).hasSize(1);
        saveBuildInfo(instantRunBuildContext, buildInfo);

        instantRunBuildContext = new InstantRunBuildContext();
        instantRunBuildContext.loadFromXmlFile(buildInfo);
        assertThat(instantRunBuildContext.getPreviousBuilds()).hasSize(2);
    }

    @Test
    public void testXmlFormat() throws ParserConfigurationException, IOException, SAXException {
        InstantRunBuildContext first = new InstantRunBuildContext();
        first.setApiLevel(new AndroidVersion(23, null /* codeName */));
        first.setDensity("xxxhdpi");
        first.addChangedFile(InstantRunBuildContext.FileType.MAIN, new File("main.apk"));
        first.addChangedFile(InstantRunBuildContext.FileType.SPLIT, new File("split.apk"));
        String buildInfo = first.toXml();

        InstantRunBuildContext second = new InstantRunBuildContext();
        second.setApiLevel(new AndroidVersion(21, null /* codeName */));
        second.setDensity("xhdpi");
        second.loadFromXml(buildInfo);
        second.addChangedFile(InstantRunBuildContext.FileType.DEX, new File("classes.dex"));
        second.addChangedFile(InstantRunBuildContext.FileType.RELOAD_DEX, new File("reload.dex"));
        buildInfo = second.toXml();

        Document document = XmlUtils.parseDocument(buildInfo, false);
        Element instantRun = (Element) document.getFirstChild();
        assertThat(instantRun.getTagName()).isEqualTo("instant-run");
        assertThat(instantRun.getAttribute(InstantRunBuildContext.ATTR_TIMESTAMP)).isEqualTo(
                String.valueOf(second.getBuildId()));
        assertThat(instantRun.getAttribute(InstantRunBuildContext.ATTR_DENSITY)).isEqualTo("xhdpi");

        // check the most recent build (called second) records :
        List<Element> secondArtifacts = getElementsByName(instantRun,
                InstantRunBuildContext.TAG_ARTIFACT);
        assertThat(secondArtifacts).hasSize(2);
        assertThat(secondArtifacts.get(0).getAttribute(InstantRunBuildContext.ATTR_TYPE))
                .isEqualTo("DEX");
        assertThat(secondArtifacts.get(0).getAttribute(InstantRunBuildContext.ATTR_LOCATION))
                .endsWith("classes.dex");
        assertThat(secondArtifacts.get(1).getAttribute(InstantRunBuildContext.ATTR_TYPE))
                .isEqualTo("RELOAD_DEX");
        assertThat(secondArtifacts.get(1).getAttribute(InstantRunBuildContext.ATTR_LOCATION))
                .endsWith("reload.dex");

        boolean foundFirst = false;
        NodeList childNodes = instantRun.getChildNodes();
        for (int i = 0; i < childNodes.getLength(); i++) {
            Node item = childNodes.item(i);
            if (item.getNodeName().equals(InstantRunBuildContext.TAG_BUILD)) {
                // there should be one build child with first build references.
                foundFirst = true;
                assertThat(((Element) item).getAttribute(InstantRunBuildContext.ATTR_TIMESTAMP))
                        .isEqualTo(
                                String.valueOf(first.getBuildId()));
                List<Element> firstArtifacts = getElementsByName(item,
                        InstantRunBuildContext.TAG_ARTIFACT);
                assertThat(firstArtifacts).hasSize(2);
                assertThat(firstArtifacts.get(0).getAttribute(InstantRunBuildContext.ATTR_TYPE))
                        .isEqualTo("MAIN");
                assertThat(firstArtifacts.get(0).getAttribute(InstantRunBuildContext.ATTR_LOCATION))
                        .endsWith("main.apk");
                assertThat(firstArtifacts.get(1).getAttribute(InstantRunBuildContext.ATTR_TYPE))
                        .isEqualTo("SPLIT");
                assertThat(firstArtifacts.get(1).getAttribute(InstantRunBuildContext.ATTR_LOCATION))
                        .endsWith("split.apk");
            }
        }
        assertThat(foundFirst).isTrue();
    }

    @Test
    public void testArtifactsPersistence()
            throws IOException, ParserConfigurationException, SAXException {
        InstantRunBuildContext instantRunBuildContext = new InstantRunBuildContext();
        instantRunBuildContext.setApiLevel(new AndroidVersion(23, null /* codeName */));
        instantRunBuildContext.addChangedFile(InstantRunBuildContext.FileType.MAIN,
                new File("main.apk"));
        instantRunBuildContext.addChangedFile(InstantRunBuildContext.FileType.SPLIT,
                new File("split.apk"));
        String buildInfo = instantRunBuildContext.toXml();

        // check xml format, the IDE depends on it.
        instantRunBuildContext = new InstantRunBuildContext();
        instantRunBuildContext.loadFromXml(buildInfo);
        assertThat(instantRunBuildContext.getPreviousBuilds()).hasSize(1);
        Build build = instantRunBuildContext.getPreviousBuilds().iterator().next();

        assertThat(build.getArtifacts()).hasSize(2);
        assertThat(build.getArtifacts().get(0).getType()).isEqualTo(
                InstantRunBuildContext.FileType.MAIN);
        assertThat(build.getArtifacts().get(1).getType()).isEqualTo(
                InstantRunBuildContext.FileType.SPLIT);
    }

    @Test
    public void testOldReloadPurge()
            throws ParserConfigurationException, IOException, SAXException {
        InstantRunBuildContext initial = new InstantRunBuildContext();
        initial.setApiLevel(new AndroidVersion(23, null /* codeName */));
        initial.addChangedFile(InstantRunBuildContext.FileType.SPLIT, new File("/tmp/split-0.apk"));
        String buildInfo = initial.toXml();

        InstantRunBuildContext first = new InstantRunBuildContext();
        first.setApiLevel(new AndroidVersion(23, null /* codeName */));
        first.loadFromXml(buildInfo);
        first.addChangedFile(InstantRunBuildContext.FileType.RELOAD_DEX,
                new File("reload.dex"));
        first.setVerifierResult(InstantRunVerifierStatus.COMPATIBLE);
        buildInfo = first.toXml();

        InstantRunBuildContext second = new InstantRunBuildContext();
        second.loadFromXml(buildInfo);
        second.setApiLevel(new AndroidVersion(23, null));
        second.addChangedFile(InstantRunBuildContext.FileType.SPLIT, new File("split.apk"));
        second.setVerifierResult(InstantRunVerifierStatus.CLASS_ANNOTATION_CHANGE);

        second.close();
        buildInfo = second.toXml();
        Document document = XmlUtils.parseDocument(buildInfo, false /* namespaceAware */);

        List<Element> builds = getElementsByName(document.getFirstChild(),
                InstantRunBuildContext.TAG_BUILD);
        // initial is never purged.
        assertThat(builds).hasSize(2);
        assertThat(builds.get(1).getAttribute(InstantRunBuildContext.ATTR_TIMESTAMP)).isEqualTo(
                String.valueOf(second.getBuildId()));
    }

    @Test
    public void testMultipleReloadCollapse()
            throws ParserConfigurationException, IOException, SAXException {
        InstantRunBuildContext initial = new InstantRunBuildContext();
        initial.setApiLevel(new AndroidVersion(23, null /* codeName */));
        initial.addChangedFile(InstantRunBuildContext.FileType.SPLIT, new File("/tmp/split-0.apk"));
        initial.close();
        String buildInfo = initial.toXml();

        InstantRunBuildContext first = new InstantRunBuildContext();
        first.loadFromXml(buildInfo);
        first.setApiLevel(new AndroidVersion(23, null /* codeName */));
        first.addChangedFile(InstantRunBuildContext.FileType.RELOAD_DEX,
                new File("reload.dex"));
        first.setVerifierResult(InstantRunVerifierStatus.COMPATIBLE);
        first.close();
        buildInfo = first.toXml();

        InstantRunBuildContext second = new InstantRunBuildContext();
        second.loadFromXml(buildInfo);
        second.setApiLevel(new AndroidVersion(23, null));
        second.addChangedFile(InstantRunBuildContext.FileType.SPLIT, new File("split.apk"));
        second.setVerifierResult(InstantRunVerifierStatus.CLASS_ANNOTATION_CHANGE);

        second.close();
        buildInfo = second.toXml();

        InstantRunBuildContext third = new InstantRunBuildContext();
        third.loadFromXml(buildInfo);
        third.setApiLevel(new AndroidVersion(23, null));
        third.addChangedFile(InstantRunBuildContext.FileType.RESOURCES,
                new File("resources-debug.ap_"));
        third.addChangedFile(InstantRunBuildContext.FileType.RELOAD_DEX, new File("reload.dex"));
        third.setVerifierResult(InstantRunVerifierStatus.COMPATIBLE);

        third.close();
        buildInfo = third.toXml();

        InstantRunBuildContext fourth = new InstantRunBuildContext();
        fourth.loadFromXml(buildInfo);
        fourth.setApiLevel(new AndroidVersion(23, null));
        fourth.addChangedFile(InstantRunBuildContext.FileType.RESOURCES,
                new File("resources-debug.ap_"));
        fourth.setVerifierResult(InstantRunVerifierStatus.COMPATIBLE);
        fourth.close();
        buildInfo = fourth.toXml();

        Document document = XmlUtils.parseDocument(buildInfo, false /* namespaceAware */);

        List<Element> builds = getElementsByName(document.getFirstChild(),
                InstantRunBuildContext.TAG_BUILD);
        // first build should have been removed due to the coldswap presence.
        assertThat(builds).hasSize(4);
        assertThat(builds.get(1).getAttribute(InstantRunBuildContext.ATTR_TIMESTAMP)).isEqualTo(
                String.valueOf(second.getBuildId()));
        assertThat(builds.get(2).getAttribute(InstantRunBuildContext.ATTR_TIMESTAMP)).isEqualTo(
                String.valueOf(third.getBuildId()));
        assertThat(getElementsByName(builds.get(2), InstantRunBuildContext.TAG_ARTIFACT))
                .named("Superseded resources.ap_ artifact should be removed.")
                .hasSize(1);

    }

    @Test
    public void testOverlappingAndEmptyChanges()
            throws ParserConfigurationException, IOException, SAXException {
        InstantRunBuildContext initial = new InstantRunBuildContext();
        initial.setApiLevel(new AndroidVersion(23, null /* codeName */));
        initial.addChangedFile(InstantRunBuildContext.FileType.SPLIT, new File("/tmp/split-0.apk"));
        String buildInfo = initial.toXml();

        InstantRunBuildContext first = new InstantRunBuildContext();
        first.loadFromXml(buildInfo);
        first.setApiLevel(new AndroidVersion(23, null /* codeName */));
        first.addChangedFile(InstantRunBuildContext.FileType.SPLIT, new File("/tmp/split-1.apk"));
        first.addChangedFile(InstantRunBuildContext.FileType.SPLIT, new File("/tmp/split-2.apk"));
        first.setVerifierResult(InstantRunVerifierStatus.CLASS_ANNOTATION_CHANGE);
        buildInfo = first.toXml();

        InstantRunBuildContext second = new InstantRunBuildContext();
        second.loadFromXml(buildInfo);
        second.setApiLevel(new AndroidVersion(23, null));
        second.addChangedFile(InstantRunBuildContext.FileType.SPLIT, new File("/tmp/split-2.apk"));
        second.setVerifierResult(InstantRunVerifierStatus.CLASS_ANNOTATION_CHANGE);
        buildInfo = second.toXml();

        InstantRunBuildContext third = new InstantRunBuildContext();
        third.loadFromXml(buildInfo);
        third.setApiLevel(new AndroidVersion(23, null));
        third.addChangedFile(InstantRunBuildContext.FileType.SPLIT, new File("/tmp/split-2.apk"));
        third.addChangedFile(InstantRunBuildContext.FileType.SPLIT, new File("/tmp/split-3.apk"));
        third.setVerifierResult(InstantRunVerifierStatus.CLASS_ANNOTATION_CHANGE);

        third.close();
        buildInfo = third.toXml();
        Document document = XmlUtils.parseDocument(buildInfo, false /* namespaceAware */);

        List<Element> builds = getElementsByName(document.getFirstChild(),
                InstantRunBuildContext.TAG_BUILD);
        // initial builds are never removed.
        // first build should have been removed due to the coldswap presence.
        assertThat(builds).hasSize(3);
        assertThat(builds.get(1).getAttribute(InstantRunBuildContext.ATTR_TIMESTAMP)).isEqualTo(
                String.valueOf(first.getBuildId()));
        List<Element> artifacts = getElementsByName(builds.get(1),
                InstantRunBuildContext.TAG_ARTIFACT);
        assertThat(artifacts).hasSize(1);
        // split-2 changes on first build is overlapped by third change.
        assertThat(artifacts.get(0).getAttribute(InstantRunBuildContext.ATTR_LOCATION))
                .isEqualTo("/tmp/split-1.apk");
        // second has been stripped.
        assertThat(builds.get(2).getAttribute(InstantRunBuildContext.ATTR_TIMESTAMP)).isEqualTo(
                String.valueOf(third.getBuildId()));
        artifacts = getElementsByName(builds.get(2), InstantRunBuildContext.TAG_ARTIFACT);
        assertThat(artifacts).hasSize(2);
        // split-2 changes on first build is overlapped by third change.
        assertThat(artifacts.get(0).getAttribute(InstantRunBuildContext.ATTR_LOCATION))
                .isEqualTo("/tmp/split-2.apk");
        assertThat(artifacts.get(1).getAttribute(InstantRunBuildContext.ATTR_LOCATION))
                .isEqualTo("/tmp/split-3.apk");
    }

    private List<Element> getElementsByName(Node parent, String nodeName) {
        ImmutableList.Builder<Element> builder = ImmutableList.builder();
        NodeList childNodes = parent.getChildNodes();
        for (int i = 0; i < childNodes.getLength(); i++) {
            Node item = childNodes.item(i);
            if (item instanceof Element && item.getNodeName().equals(nodeName)) {
                builder.add((Element) item);
            }
        }
        return builder.build();
    }

    private static File createMarkedBuildInfo() throws IOException, ParserConfigurationException {
        InstantRunBuildContext originalContext = new InstantRunBuildContext();
        return createBuildInfo(originalContext);
    }

    private static File createBuildInfo(InstantRunBuildContext context)
            throws IOException, ParserConfigurationException {
        File tmpFile = File.createTempFile("InstantRunBuildContext", "tmp");
        saveBuildInfo(context, tmpFile);
        tmpFile.deleteOnExit();
        return tmpFile;
    }

    private static void saveBuildInfo(InstantRunBuildContext context, File buildInfo)
            throws IOException, ParserConfigurationException {
        String xml = context.toXml();
        Files.write(xml, buildInfo, Charsets.UTF_8);
    }
}