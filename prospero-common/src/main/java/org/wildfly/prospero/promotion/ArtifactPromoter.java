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
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.deployment.DeployRequest;
import org.eclipse.aether.deployment.DeploymentException;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.VersionRangeRequest;
import org.eclipse.aether.resolution.VersionRangeResolutionException;
import org.eclipse.aether.resolution.VersionRangeResult;
import org.jboss.logging.Logger;
import org.wildfly.channel.ArtifactCoordinate;
import org.wildfly.channel.Channel;
import org.wildfly.channel.ChannelMapper;
import org.wildfly.channel.Stream;
import org.wildfly.channel.maven.ChannelCoordinate;
import org.wildfly.channel.maven.VersionResolverFactory;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class ArtifactPromoter {

    private static final Logger log = Logger.getLogger(ArtifactPromoter.class);

    private RepositorySystem system;
    private DefaultRepositorySystemSession session;
    private RemoteRepository targetRepository;

    public ArtifactPromoter(RepositorySystem system, DefaultRepositorySystemSession session, RemoteRepository targetRepository) {
        this.system = system;
        this.session = session;
        this.targetRepository = targetRepository;

        if (!targetRepository.getProtocol().equals("file")) {
            throw new IllegalArgumentException("Promoting to non-file repositories is not currently supported");
        }
    }

    public void promote(List<ArtifactCoordinate> artifacts, ChannelCoordinate coordinate, RemoteRepository sourceRepository) throws ArtifactResolutionException, DeploymentException, IOException {
        Objects.requireNonNull(artifacts);
        Objects.requireNonNull(coordinate);

        if (artifacts.isEmpty()) {
            log.debug("No artifacts to promote");
            return;
        }

        final List<ArtifactResult> results = resolveArtifactsFromBundle(artifacts, sourceRepository);

        deployResolvedArtifacts(results);

        List<Stream> streams = artifacts.stream()
                .map(a->new Stream(a.getGroupId(), a.getArtifactId(), a.getVersion()))
                .collect(Collectors.toList());

        // get most recent channel version
        VersionRangeRequest vr = new VersionRangeRequest(new DefaultArtifact(coordinate.getGroupId(), coordinate.getArtifactId(),
                coordinate.getClassifier(), coordinate.getExtension(), "[0,)"), Arrays.asList(targetRepository), null);


        Optional<String> version = latestAvailableChannelVersion(vr);

        final Channel channel = resolveDeployedChannel(coordinate, version);

        if (!channel.getStreams().addAll(streams)) {
            return;
        }

        deployChannel(coordinate, version, channel);
    }

    private List<ArtifactResult> resolveArtifactsFromBundle(List<ArtifactCoordinate> artifacts, RemoteRepository sourceRepository) throws ArtifactResolutionException {
        log.debugf("Resolving %s artifacts from custom bundle at %s", artifacts.size(), sourceRepository.getUrl());
        final List<RemoteRepository> repositories = Arrays.asList(sourceRepository);
        // generate maven requests
        List<ArtifactRequest> requests = artifacts.stream().map(artifact -> {
            final String extension = (artifact.getExtension() == null || artifact.getExtension().isEmpty()) ? "jar" : artifact.getExtension();
            Artifact mavenArtifact = new DefaultArtifact(artifact.getGroupId(), artifact.getArtifactId(), artifact.getClassifier(), extension, artifact.getVersion());
            return new ArtifactRequest(mavenArtifact, repositories, null);
        }).collect(Collectors.toList());

        return system.resolveArtifacts(session, requests);
    }

    private void deployResolvedArtifacts(List<ArtifactResult> results) throws DeploymentException {
        log.debugf("Deploying %s artifacts from custom bundle to %s", results.size(), targetRepository.getUrl());
        final DeployRequest deployRequest = new DeployRequest();
        deployRequest.setRepository(targetRepository);
        results.stream().forEach(result -> deployRequest.setArtifacts(Arrays.asList(result.getArtifact())));
        system.deploy(session, deployRequest);
    }

    private Optional<String> latestAvailableChannelVersion(VersionRangeRequest vr) {
        try {
            final VersionRangeResult result = system.resolveVersionRange(session, vr);
            if (result.getHighestVersion() != null) {
                return Optional.of(result.getHighestVersion().toString());
            }
        } catch (VersionRangeResolutionException e) {

            // OK, no custom channel exists so far, we'll create one
        }
        return Optional.empty();
    }

    private Channel resolveDeployedChannel(ChannelCoordinate coordinate, Optional<String> version) throws MalformedURLException {

        if (version.isPresent()) {
            log.debugf("Found existing customization channel with version %s", version.get());
            ChannelCoordinate fullCoordinate = new ChannelCoordinate(coordinate.getGroupId(), coordinate.getArtifactId(), version.get());

            return new VersionResolverFactory(system, session, Arrays.asList(targetRepository))
                    .resolveChannels(Arrays.asList(fullCoordinate)).get(0);
        } else {
            log.debugf("No existing customization channel found, creating new channel");
            return new Channel("custom-channel", "Customization channel", null, null, new ArrayList<>());
        }
    }

    private void deployChannel(ChannelCoordinate coordinate, Optional<String> version, Channel channel) throws IOException, DeploymentException {
        final Path tempFile = Files.createTempFile("channel", "yaml");
        try {
            log.debugf("Writing new customization channel to %s", tempFile);
            Files.writeString(tempFile, ChannelMapper.toYaml(channel));
            String newVersion = incrementVersion(version.orElse("1.0.0.Final-rev00000001"));


            log.debugf("Deploying new customization channel as version %s to %s", newVersion, targetRepository);
            final DefaultArtifact channelArtifact = new DefaultArtifact(coordinate.getGroupId(), coordinate.getArtifactId(),
                    "channel", "yaml", newVersion, null, tempFile.toFile());
            channelArtifact.setFile(tempFile.toFile());

            final DeployRequest deployRequest = new DeployRequest();
            deployRequest.setRepository(targetRepository);
            deployRequest.setArtifacts(Arrays.asList(channelArtifact));
            system.deploy(session, deployRequest);
        } finally {
            Files.delete(tempFile);
        }
    }

    private String incrementVersion(String baseVersion) {
        final Pattern versionSuffixFormat = Pattern.compile(".*-rev\\d{8}");
        if (!versionSuffixFormat.matcher(baseVersion).matches()) {
            throw new IllegalArgumentException("Wrong format of custom channel version " + baseVersion);
        }

        final String suffix = "-rev";
        final int suffixIndex = baseVersion.lastIndexOf(suffix) + suffix.length();
        final String suffixVersion = baseVersion.substring(suffixIndex);
        final String coreVersion = baseVersion.substring(0, suffixIndex);

        int currentVersion = Integer.parseInt(suffixVersion);

        if (currentVersion == 99_999_999) {
            throw new IllegalArgumentException("Custom channel version exceeded limit " + baseVersion);
        }

        return String.format("%s%08d", coreVersion, (currentVersion + 1));
    }
}
