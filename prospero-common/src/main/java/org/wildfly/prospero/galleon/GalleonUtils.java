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

package org.wildfly.prospero.galleon;

import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.ProvisioningManager;
import org.jboss.galleon.state.ProvisionedFeaturePack;
import org.jboss.galleon.universe.maven.repo.MavenRepoManager;
import org.jboss.galleon.util.PathsUtils;
import org.jboss.galleon.xml.ProvisionedStateXmlParser;
import org.wildfly.channel.UnresolvedMavenArtifactException;

import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class GalleonUtils {

    public static final String MAVEN_REPO_LOCAL = "maven.repo.local";
    public static final String JBOSS_FORK_EMBEDDED_PROPERTY = "jboss-fork-embedded";
    public static final String JBOSS_FORK_EMBEDDED_VALUE = "true";
    public static final String JBOSS_BULK_RESOLVE_PROPERTY = "jboss-bulk-resolve-artifacts";
    public static final String JBOSS_BULK_RESOLVE_VALUE = "true";
    public static final String MODULE_PATH_PROPERTY = "module.path";
    public static final String PRINT_ONLY_CONFLICTS_PROPERTY = "print-only-conflicts";
    public static final String PRINT_ONLY_CONFLICTS_VALUE = "true";

    public static void executeGalleon(GalleonExecution execution, Path localRepository) throws ProvisioningException, UnresolvedMavenArtifactException {
        final String modulePathProperty = System.getProperty(MODULE_PATH_PROPERTY);

        try {
            System.setProperty(MAVEN_REPO_LOCAL, localRepository.toString());
            if (modulePathProperty != null) {
                System.clearProperty(MODULE_PATH_PROPERTY);
            }
            final Map<String, String> options = new HashMap<>();
            options.put(GalleonUtils.JBOSS_FORK_EMBEDDED_PROPERTY, GalleonUtils.JBOSS_FORK_EMBEDDED_VALUE);
            options.put(GalleonUtils.JBOSS_BULK_RESOLVE_PROPERTY, GalleonUtils.JBOSS_BULK_RESOLVE_VALUE);
            options.put(GalleonUtils.PRINT_ONLY_CONFLICTS_PROPERTY, GalleonUtils.PRINT_ONLY_CONFLICTS_VALUE);
            execution.execute(options);
        } catch (ProvisioningException e) {
            throw extractMavenException(e).orElseThrow(()->e);
        } finally {
            System.clearProperty(MAVEN_REPO_LOCAL);
            if (modulePathProperty != null) {
                System.setProperty(MODULE_PATH_PROPERTY, modulePathProperty);
            }
        }
    }

    private static Optional<UnresolvedMavenArtifactException> extractMavenException(Throwable e) {
        if (e instanceof UnresolvedMavenArtifactException) {
            return Optional.of((UnresolvedMavenArtifactException) e);
        } else if (e.getCause() != null) {
            return extractMavenException(e.getCause());
        }
        return Optional.empty();
    }

    public interface GalleonExecution {
        void execute(Map<String, String> options) throws ProvisioningException;
    }

    public static ProvisioningManager getProvisioningManager(Path installDir, MavenRepoManager maven) throws ProvisioningException {
        ProvisioningManager provMgr = ProvisioningManager.builder().addArtifactResolver(maven)
                .setInstallationHome(installDir).build();
        return provMgr;
    }

    public static List<String> getInstalledPacks(Path dir) throws ProvisioningException {
        final Collection<ProvisionedFeaturePack> featurePacks = ProvisionedStateXmlParser.parse(
                PathsUtils.getProvisionedStateXml(dir)).getFeaturePacks();

        return featurePacks.stream().map(fp -> fp.getFPID().getProducer().getName()).collect(Collectors.toList());
    }
}
