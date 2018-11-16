/*
 * SonarQube
 * Copyright (C) 2009-2019 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.ce.task.projectanalysis.component;

import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import javax.annotation.CheckForNull;
import javax.annotation.Nullable;
import org.sonar.api.internal.apachecommons.lang.StringUtils;
import org.sonar.ce.task.projectanalysis.analysis.Branch;
import org.sonar.core.util.stream.MoreCollectors;
import org.sonar.db.component.SnapshotDto;
import org.sonar.scanner.protocol.output.ScannerReport;
import org.sonar.scanner.protocol.output.ScannerReport.Component.FileStatus;
import org.sonar.server.project.Project;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkArgument;
import static java.lang.String.format;
import static org.apache.commons.lang.StringUtils.trimToNull;

public class ComponentTreeBuilder {

  private static final String DEFAULT_PROJECT_VERSION = "not provided";

  private final ComponentKeyGenerator keyGenerator;
  private final ComponentKeyGenerator publicKeyGenerator;
  /**
   * Will supply the UUID for any component in the tree, given it's key.
   * <p>
   * The String argument of the {@link Function#apply(Object)} method is the component's key.
   * </p>
   */
  private final Function<String, String> uuidSupplier;
  /**
   * Will supply the {@link ScannerReport.Component} of all the components in the component tree as we crawl it from the
   * root.
   * <p>
   * The Integer argument of the {@link Function#apply(Object)} method is the component's ref.
   * </p>
   */
  private final Function<Integer, ScannerReport.Component> scannerComponentSupplier;
  private final Project project;
  private final Branch branch;
  @Nullable
  private final SnapshotDto baseAnalysis;

  private ScannerReport.Component rootComponent;
  private String scmBasePath;

  public ComponentTreeBuilder(
    ComponentKeyGenerator keyGenerator,
    ComponentKeyGenerator publicKeyGenerator,
    Function<String, String> uuidSupplier,
    Function<Integer, ScannerReport.Component> scannerComponentSupplier,
    Project project,
    Branch branch, @Nullable SnapshotDto baseAnalysis) {

    this.keyGenerator = keyGenerator;
    this.publicKeyGenerator = publicKeyGenerator;
    this.uuidSupplier = uuidSupplier;
    this.scannerComponentSupplier = scannerComponentSupplier;
    this.project = project;
    this.branch = branch;
    this.baseAnalysis = baseAnalysis;
  }

  public Component buildProject(ScannerReport.Component project, String scmBasePath) {
    this.rootComponent = project;
    this.scmBasePath = trimToNull(scmBasePath);

    Node root = buildFolderHierarchy(project);
    return buildNode(root, "");
  }

  private Node buildFolderHierarchy(ScannerReport.Component rootComponent) {
    Preconditions.checkArgument(rootComponent.getType() == ScannerReport.Component.ComponentType.PROJECT, "Expected root component of type 'PROJECT'");

    LinkedList<ScannerReport.Component> queue = new LinkedList<>();
    rootComponent.getChildRefList()
      .stream()
      .map(scannerComponentSupplier)
      .forEach(queue::addLast);

    Node root = new Node();
    root.reportComponent = rootComponent;

    while (!queue.isEmpty()) {
      ScannerReport.Component component = queue.removeFirst();
      switch (component.getType()) {
        case FILE:
          addFileOrDirectory(root, component);
          break;
        case MODULE:

          component.getChildRefList().stream()
            .map(scannerComponentSupplier)
            .forEach(queue::addLast);
          break;
        case DIRECTORY:
          addFileOrDirectory(root, component);
          component.getChildRefList().stream()
            .map(scannerComponentSupplier)
            .forEach(queue::addLast);
          break;
        default:
          throw new IllegalArgumentException(format("Unsupported component type '%s'", component.getType()));
      }
    }
    return root;
  }

  private static void addFileOrDirectory(Node root, ScannerReport.Component file) {
    Preconditions.checkArgument(file.getType() != ScannerReport.Component.ComponentType.FILE || !StringUtils.isEmpty(file.getProjectRelativePath()),
      "Files should have a relative path: " + file);
    String[] split = StringUtils.split(file.getProjectRelativePath(), '/');
    Node currentNode = root.children().computeIfAbsent("", k -> new Node());

    for (int i = 0; i < split.length; i++) {
      currentNode = currentNode.children().computeIfAbsent(split[i], k -> new Node());
    }
    currentNode.reportComponent = file;
  }

  private Component buildNode(Node node, String currentPath) {
    List<Component> childComponents = buildChildren(node, currentPath);
    ScannerReport.Component component = node.reportComponent();

    if (component != null) {
      if (component.getType() == ScannerReport.Component.ComponentType.FILE) {
        return buildFile(component);
      } else if (component.getType() == ScannerReport.Component.ComponentType.PROJECT) {
        return buildProject(childComponents);
      }
    }

    return buildDirectory(currentPath, component, childComponents);
  }

  private List<Component> buildChildren(Node node, String currentPath) {
    List<Component> children = new ArrayList<>();

    for (Map.Entry<String, Node> e : node.children().entrySet()) {
      String path = buildPath(currentPath, e.getKey());
      Node n = e.getValue();

      while (n.children().size() == 1 && n.children().values().iterator().next().children().size() > 0) {
        Map.Entry<String, Node> childEntry = n.children().entrySet().iterator().next();
        path = buildPath(path, childEntry.getKey());
        n = childEntry.getValue();
      }
      children.add(buildNode(n, path));
    }
    return children;
  }

  private static String buildPath(String currentPath, String file) {
    if (currentPath.isEmpty()) {
      return file;
    }
    return currentPath + "/" + file;
  }

  private Component buildProject(List<Component> children) {
    String projectKey = keyGenerator.generateKey(rootComponent, null);
    String uuid = uuidSupplier.apply(projectKey);
    String projectPublicKey = publicKeyGenerator.generateKey(rootComponent, null);
    ComponentImpl.Builder builder = ComponentImpl.builder(Component.Type.PROJECT)
      .setUuid(uuid)
      .setDbKey(projectKey)
      .setKey(projectPublicKey)
      .setStatus(convertStatus(rootComponent.getStatus()))
      .setProjectAttributes(new ProjectAttributes(createProjectVersion(rootComponent)))
      .setReportAttributes(createAttributesBuilder(rootComponent.getRef(), rootComponent.getProjectRelativePath(), scmBasePath,
        rootComponent.getProjectRelativePath()).build())
      .addChildren(children);
    setNameAndDescription(rootComponent, builder);
    return builder.build();
  }

  private ComponentImpl buildFile(ScannerReport.Component component) {
    String key = keyGenerator.generateKey(rootComponent, component.getProjectRelativePath());
    String publicKey = publicKeyGenerator.generateKey(rootComponent, component.getProjectRelativePath());
    return ComponentImpl.builder(Component.Type.FILE)
      .setUuid(uuidSupplier.apply(key))
      .setDbKey(key)
      .setKey(publicKey)
      .setName(nameOfOthers(component, publicKey))
      .setStatus(convertStatus(component.getStatus()))
      .setDescription(trimToNull(component.getDescription()))
      .setReportAttributes(createAttributesBuilder(component.getRef(), component.getProjectRelativePath(), scmBasePath, component.getProjectRelativePath()).build())
      .setFileAttributes(createFileAttributes(component))
      .build();
  }

  private ComponentImpl buildDirectory(String path, @Nullable ScannerReport.Component scannerComponent, List<Component> children) {
    String nonEmptyPath = path.isEmpty() ? "/" : path;
    String key = keyGenerator.generateKey(rootComponent, nonEmptyPath);
    String publicKey = publicKeyGenerator.generateKey(rootComponent, nonEmptyPath);
    Integer ref = scannerComponent != null ? scannerComponent.getRef() : null;
    return ComponentImpl.builder(Component.Type.DIRECTORY)
      .setUuid(uuidSupplier.apply(key))
      .setDbKey(key)
      .setKey(publicKey)
      .setName(publicKey)
      .setStatus(convertStatus(FileStatus.UNAVAILABLE))
      .setReportAttributes(createAttributesBuilder(ref, nonEmptyPath, scmBasePath, path).build())
      .addChildren(children)
      .build();
  }

  public Component buildChangedComponentTreeRoot(Component project) {
    return buildChangedComponentTree(project);
  }

  private static ComponentImpl.Builder changedComponentBuilder(Component component) {
    return ComponentImpl.builder(component.getType())
      .setUuid(component.getUuid())
      .setDbKey(component.getDbKey())
      .setKey(component.getKey())
      .setStatus(component.getStatus())
      .setReportAttributes(component.getReportAttributes())
      .setName(component.getName())
      .setDescription(component.getDescription());
  }

  @Nullable
  private static Component buildChangedComponentTree(Component component) {
    switch (component.getType()) {
      case PROJECT:
        return buildChangedProject(component);
      case DIRECTORY:
        return buildChangedIntermediate(component);
      case FILE:
        return buildChangedFile(component);

      default:
        throw new IllegalArgumentException(format("Unsupported component type '%s'", component.getType()));
    }
  }

  private static Component buildChangedProject(Component component) {
    return changedComponentBuilder(component)
      .setProjectAttributes(new ProjectAttributes(component.getProjectAttributes().getVersion()))
      .addChildren(buildChangedComponentChildren(component))
      .build();
  }

  @Nullable
  private static Component buildChangedIntermediate(Component component) {
    List<Component> children = buildChangedComponentChildren(component);
    if (children.isEmpty()) {
      return null;
    }
    return changedComponentBuilder(component)
      .addChildren(children)
      .build();
  }

  @Nullable
  private static Component buildChangedFile(Component component) {
    if (component.getStatus() == Component.Status.SAME) {
      return null;
    }
    return changedComponentBuilder(component)
      .setFileAttributes(component.getFileAttributes())
      .build();
  }

  private static List<Component> buildChangedComponentChildren(Component component) {
    return component.getChildren()
      .stream()
      .map(ComponentTreeBuilder::buildChangedComponentTree)
      .filter(Objects::nonNull)
      .collect(MoreCollectors.toList());
  }

  private void setNameAndDescription(ScannerReport.Component component, ComponentImpl.Builder builder) {
    if (branch.isMain()) {
      builder
        .setName(nameOfProject(component))
        .setDescription(component.getDescription());
    } else {
      builder
        .setName(project.getName())
        .setDescription(project.getDescription());
    }
  }

  private static Component.Status convertStatus(FileStatus status) {
    switch (status) {
      case ADDED:
        return Component.Status.ADDED;
      case SAME:
        return Component.Status.SAME;
      case CHANGED:
        return Component.Status.CHANGED;
      case UNAVAILABLE:
        return Component.Status.UNAVAILABLE;
      case UNRECOGNIZED:
      default:
        throw new IllegalArgumentException("Unsupported ComponentType value " + status);
    }
  }

  private String nameOfProject(ScannerReport.Component component) {
    String name = trimToNull(component.getName());
    if (name != null) {
      return name;
    }
    return project.getName();
  }

  private static String nameOfOthers(ScannerReport.Component reportComponent, String defaultName) {
    String name = trimToNull(reportComponent.getName());
    return name == null ? defaultName : name;
  }

  private String createProjectVersion(ScannerReport.Component component) {
    String version = trimToNull(component.getVersion());
    if (version != null) {
      return version;
    }
    if (baseAnalysis != null) {
      return firstNonNull(baseAnalysis.getVersion(), DEFAULT_PROJECT_VERSION);
    }
    return DEFAULT_PROJECT_VERSION;
  }

  private static ReportAttributes.Builder createAttributesBuilder(@Nullable Integer ref, String path, @Nullable String scmBasePath, String scmRelativePath) {
    return ReportAttributes.newBuilder(ref)
      .setPath(trimToNull(path))
      .setScmPath(computeScmPath(scmBasePath, scmRelativePath));
  }

  @CheckForNull
  private static String computeScmPath(@Nullable String scmBasePath, String scmRelativePath) {
    if (scmRelativePath.isEmpty()) {
      return null;
    }
    if (scmBasePath == null) {
      return scmRelativePath;
    }

    return scmBasePath + '/' + scmRelativePath;
  }

  private static FileAttributes createFileAttributes(ScannerReport.Component component) {
    checkArgument(component.getType() == ScannerReport.Component.ComponentType.FILE);
    checkArgument(component.getLines() > 0, "File '%s' has no line", component.getProjectRelativePath());
    return new FileAttributes(
      component.getIsTest(),
      trimToNull(component.getLanguage()),
      component.getLines());
  }

  private static class Node {
    private final Map<String, Node> children = new LinkedHashMap<>();
    private ScannerReport.Component reportComponent;

    private Map<String, Node> children() {
      return children;
    }

    @CheckForNull
    private ScannerReport.Component reportComponent() {
      return reportComponent;
    }
  }
}
