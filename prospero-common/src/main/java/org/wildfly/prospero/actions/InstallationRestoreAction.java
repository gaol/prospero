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

package org.wildfly.prospero.actions;

import org.wildfly.channel.UnresolvedMavenArtifactException;
import org.wildfly.prospero.Messages;
import org.wildfly.prospero.api.exceptions.ArtifactResolutionException;
import org.wildfly.prospero.api.exceptions.OperationException;
import org.wildfly.prospero.galleon.GalleonEnvironment;
import org.wildfly.prospero.model.ChannelRef;
import org.wildfly.prospero.api.InstallationMetadata;
import org.wildfly.prospero.api.exceptions.MetadataException;
import org.wildfly.prospero.galleon.GalleonUtils;
import org.wildfly.prospero.galleon.ChannelMavenArtifactRepositoryManager;
import org.wildfly.prospero.model.ProsperoConfig;
import org.wildfly.prospero.wfchannel.MavenSessionManager;
import org.eclipse.aether.repository.RemoteRepository;
import org.jboss.galleon.ProvisioningException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public class InstallationRestoreAction {

    private final Path installDir;
    private final Console console;
    private MavenSessionManager mavenSessionManager;

    public InstallationRestoreAction(Path installDir, MavenSessionManager mavenSessionManager, Console console) {
        this.installDir = installDir;
        this.mavenSessionManager = mavenSessionManager;
        this.console = console;
    }

    public void restore(Path metadataBundleZip)
            throws ProvisioningException, IOException, OperationException {
        if (installDir.toFile().exists()) {
            throw Messages.MESSAGES.installationDirAlreadyExists(installDir);
        }

        try (final InstallationMetadata metadataBundle = InstallationMetadata.importMetadata(metadataBundleZip)) {
            final ProsperoConfig prosperoConfig = metadataBundle.getProsperoConfig();
            final GalleonEnvironment galleonEnv = GalleonEnvironment
                    .builder(installDir, prosperoConfig, mavenSessionManager)
                    .setConsole(console)
                    .setRestoreManifest(metadataBundle.getManifest())
                    .build();

            try {
                GalleonUtils.executeGalleon(options -> galleonEnv.getProvisioningManager().provision(metadataBundle.getGalleonProvisioningConfig(), options),
                        mavenSessionManager.getProvisioningRepo().toAbsolutePath());
            } catch (UnresolvedMavenArtifactException e) {
                throw new ArtifactResolutionException(e, prosperoConfig.getRemoteRepositories(), mavenSessionManager.isOffline());
            }

            writeProsperoMetadata(galleonEnv.getRepositoryManager(), prosperoConfig.getChannels(), prosperoConfig.getRemoteRepositories());
        }
    }

    private void writeProsperoMetadata(ChannelMavenArtifactRepositoryManager maven, List<ChannelRef> channelRefs, List<RemoteRepository> repositories)
            throws MetadataException {
        try (final InstallationMetadata installationMetadata = new InstallationMetadata(installDir, maven.resolvedChannel(), channelRefs, repositories)) {
            installationMetadata.recordProvision(true);
        }
    }
}
