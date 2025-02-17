/*
 * Copyright 2022 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wildfly.prospero.promotion;

import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.deployment.DeploymentException;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.VersionRangeRequest;
import org.eclipse.aether.resolution.VersionRangeResult;
import org.eclipse.aether.version.Version;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.wildfly.channel.Channel;
import org.wildfly.channel.ChannelMapper;
import org.wildfly.channel.Stream;
import org.wildfly.channel.UnresolvedMavenArtifactException;
import org.wildfly.channel.maven.ChannelCoordinate;
import org.wildfly.channel.maven.VersionResolverFactory;
import org.wildfly.prospero.wfchannel.MavenSessionManager;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ArtifactPromoterTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private MavenSessionManager sessionManager;
    private RepositorySystem system;
    private DefaultRepositorySystemSession session;
    private Path targetRepositoryPath;
    private Path sourceRepositoryPath;
    private RemoteRepository targetRepository;
    private RemoteRepository sourceRepository;

    @Before
    public void setUp() throws Exception {
        sessionManager = new MavenSessionManager();
        system = sessionManager.newRepositorySystem();
        session = sessionManager.newRepositorySystemSession(system, false);

        sourceRepositoryPath = temp.newFolder("source").toPath();
        targetRepositoryPath = temp.newFolder("target").toPath();
        sourceRepository = new RemoteRepository.Builder("source", "default", sourceRepositoryPath.toUri().toURL().toString()).build();
        targetRepository = new RemoteRepository.Builder("target", "default", targetRepositoryPath.toUri().toURL().toString()).build();
    }

    @Test
    public void testPromoteSingleArtifactIntoEmptyChannel() throws Exception {
        final CustomArtifact artifact = new CustomArtifact("foo", "bar", null, "jar", "1.2.3");
        mockDeployArtifact(artifact, sourceRepositoryPath);

        final ChannelCoordinate channelGa = new ChannelCoordinate("test", "channel");
        final CustomArtifactList artifactList = new CustomArtifactList(Arrays.asList(artifact));
        promote(artifactList, channelGa);

        assertArtifactInRepository(artifact);
        assertStreamMatches("foo", "bar", "1.2.3", channelGa);
    }

    @Test
    public void testPromoteSingleArtifactIntoPreExistingChannel() throws Exception {
        final CustomArtifact artifact = new CustomArtifact("stream", "two", null, "jar", "1.2.3");
        mockDeployArtifact(artifact, sourceRepositoryPath);

        List<Stream> streams = Arrays.asList(new Stream("stream", "one", "1.2.3"));
        mockDeployedChannel(streams, "1.0.0.Final-rev00000001");

        final ChannelCoordinate channelGa = new ChannelCoordinate("test", "channel");
        promote(new CustomArtifactList(Arrays.asList(artifact)), channelGa);

        assertArtifactInRepository(artifact);
        assertStreamMatches("stream", "two", "1.2.3", channelGa);
        assertStreamMatches("stream", "one", "1.2.3", channelGa);
    }

    private void mockDeployArtifact(CustomArtifact artifact, Path sourceRepositoryPath) throws IOException {
        Files.createDirectories(artifactPath(artifact, sourceRepositoryPath).getParent());
        Files.createFile(artifactPath(artifact, sourceRepositoryPath));
    }

    @Test
    public void testPromoteNoArtifacts() throws Exception {
        final ChannelCoordinate channelGa = new ChannelCoordinate("test", "channel");
        final CustomArtifactList artifactList = new CustomArtifactList(Collections.emptyList());
        promote(artifactList, channelGa);

        assertChannelFileNotCreated(channelGa);
    }

    @Test
    public void testArtifactNotAvailableInArchive() throws Exception {
        final CustomArtifact artifact = new CustomArtifact("foo", "bar", null, "jar", "1.2.3");
        final ChannelCoordinate channelGa = new ChannelCoordinate("test", "channel");
        final CustomArtifactList artifactList = new CustomArtifactList(Arrays.asList(artifact));
        try {
            promote(artifactList, channelGa);
            fail("Should fail to resolve non-existing artifact");
        } catch (ArtifactResolutionException e) {
            // OK
        }

        assertChannelFileNotCreated(channelGa);
    }

    @Test
    public void testPromoteAlreadyExistingArtifactIntoEmptyChannel() throws Exception {
        final CustomArtifact artifact = new CustomArtifact("foo", "bar", null, "jar", "1.2.3");
        mockDeployArtifact(artifact, sourceRepositoryPath);
        mockDeployArtifact(artifact, targetRepositoryPath);

        final ChannelCoordinate channelGa = new ChannelCoordinate("test", "channel");
        final CustomArtifactList artifactList = new CustomArtifactList(Arrays.asList(artifact));
        promote(artifactList, channelGa);

        assertArtifactInRepository(artifact);
        assertStreamMatches("foo", "bar", "1.2.3", channelGa);
    }

    @Test
    public void testPromoteArtifactNoChangesInChannel() throws Exception {
        final CustomArtifact artifact = new CustomArtifact("stream", "one", null, "jar", "1.2.3");
        mockDeployArtifact(artifact, sourceRepositoryPath);
        mockDeployArtifact(artifact, targetRepositoryPath);

        List<Stream> streams = Arrays.asList(new Stream("stream", "one", "1.2.3"));
        mockDeployedChannel(streams, "1.0.0.Final-rev00000001");

        final ChannelCoordinate channelGa = new ChannelCoordinate("test", "channel");
        promote(new CustomArtifactList(Arrays.asList(artifact)), channelGa);

        final VersionRangeRequest request = new VersionRangeRequest(
                new DefaultArtifact("test", "channel",  "channel", "yaml", "[0,)"),
                Arrays.asList(targetRepository), null);
        VersionRangeResult versions = system.resolveVersionRange(session, request);
        assertThat(versions.getVersions().stream().map(Version::toString)).containsOnly(
                "1.0.0.Final-rev00000001"
        );
    }

    @Test
    public void testTooManyChannelVersions() throws Exception {
        final CustomArtifact artifact = new CustomArtifact("stream", "two", null, "jar", "1.2.3");
        mockDeployArtifact(artifact, sourceRepositoryPath);

        List<Stream> streams = Arrays.asList(new Stream("stream", "one", "1.2.3"));
        mockDeployedChannel(streams, "1.0.0.Final-rev99999999");

        final ChannelCoordinate channelGa = new ChannelCoordinate("test", "channel");
        try {
            promote(new CustomArtifactList(Arrays.asList(artifact)), channelGa);
            fail("Should not be possible to create another channel version");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("Custom channel version exceeded limit"));
        }
    }

    @Test
    public void testExistingChannelHasUnexpectedVersionFormat() throws Exception {
        final CustomArtifact artifact = new CustomArtifact("stream", "two", null, "jar", "1.2.3");
        mockDeployArtifact(artifact, sourceRepositoryPath);

        List<Stream> streams = Arrays.asList(new Stream("stream", "one", "1.2.3"));
        mockDeployedChannel(streams, "1.0.0.Final-wrongsuffix");

        final ChannelCoordinate channelGa = new ChannelCoordinate("test", "channel");
        try {
            promote(new CustomArtifactList(Arrays.asList(artifact)), channelGa);
            fail("Should not be possible to create another channel version");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("Wrong format of custom channel version"));
        }
    }

    private void mockDeployedChannel(List<Stream> streams, String version) throws IOException {
        Channel channel = new Channel("custom-channel", null, null, null, streams);
        final CustomArtifact existingChannel = new CustomArtifact("test", "channel",  "channel", "yaml", version);
        Files.createDirectories(artifactPath(existingChannel, targetRepositoryPath).getParent());

        final Path file = Files.createFile(artifactPath(existingChannel, targetRepositoryPath));
        Files.writeString(file, ChannelMapper.toYaml(channel));
        Files.writeString(file.getParent().getParent().resolve("maven-metadata.xml"),
                "<metadata>" +
                        "<groupId>test</groupId>" +
                        "<artifactId>channel</artifactId>" +
                        "<versioning>" +
                        "<versions><version>" + version + "</version></versions>" +
                        "</versioning>" +
                        "</metadata>");
    }

    private void assertChannelFileNotCreated(ChannelCoordinate channelGa) throws MalformedURLException {
        try {
            getChannels(channelGa).isEmpty();
            fail("The channel file should not have been created when no artifacts are given");
        } catch (UnresolvedMavenArtifactException e) {
            // OK
        }
    }

    private void assertStreamMatches(String groupId, String artifactId, String version, ChannelCoordinate channelGa) throws MalformedURLException {

        final List<Channel> channels = getChannels(channelGa);

        final Channel channel = channels.get(0);
        final Optional<Stream> stream = channel.getStreams().stream()
                .filter(s -> s.getGroupId().equals(groupId) && s.getArtifactId().equals(artifactId))
                .findFirst();
        assertTrue(stream.isPresent());
        assertEquals(version, stream.get().getVersion());
    }

    private List<Channel> getChannels(ChannelCoordinate channelGa) throws MalformedURLException {
        return new VersionResolverFactory(system, session, Arrays.asList(targetRepository))
                .resolveChannels(Arrays.asList(channelGa));
    }

    private void assertArtifactInRepository(CustomArtifact artifactCoordinate) throws IOException {
        Path artifactPath = artifactPath(artifactCoordinate, targetRepositoryPath);

        assertTrue(Files.exists(artifactPath));
    }

    private Path artifactPath(CustomArtifact artifactCoordinate, Path repository) {
        return repository
                .resolve(artifactCoordinate.getGroupId().replace(".", File.separator))
                .resolve(artifactCoordinate.getArtifactId())
                .resolve(artifactCoordinate.getVersion())
                .resolve(String.format("%s-%s%s.%s", artifactCoordinate.getArtifactId(), artifactCoordinate.getVersion(),
                        artifactCoordinate.getClassifier()==null?"":("-"+ artifactCoordinate.getClassifier()),
                        artifactCoordinate.getExtension()));
    }

    private void promote(CustomArtifactList artifacts, ChannelCoordinate coordinate) throws IOException, ArtifactResolutionException, DeploymentException {
        final ArtifactPromoter artifactPromoter = new ArtifactPromoter(system, session, targetRepository);
        artifactPromoter.promote(artifacts.getArtifactCoordinates(), coordinate, sourceRepository);
    }
}
