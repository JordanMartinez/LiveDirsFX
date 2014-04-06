package org.fxmisc.livedirs;

import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.NoSuchElementException;
import java.util.Set;

import javafx.collections.ObservableList;
import javafx.scene.Node;
import javafx.scene.control.TreeItem;

import org.fxmisc.livedirs.LiveDirs.GraphicFactory;

abstract class PathItem extends TreeItem<Path> {
    protected PathItem(Path path, Node graphic) {
        super(path, graphic);
    }

    @Override
    public final boolean isLeaf() {
        return !isDirectory();
    }

    public abstract boolean isDirectory();

    public FileItem asFileItem() { return (FileItem) this; }
    public DirItem asDirItem() { return (DirItem) this; }

    public PathItem getRelChild(Path relPath) {
        assert relPath.getNameCount() == 1;
        Path childValue = getValue().resolve(relPath);
        for(TreeItem<Path> ch: getChildren()) {
            if(ch.getValue().equals(childValue)) {
                return (PathItem) ch;
            }
        }
        return null;
    }

    protected PathItem resolve(Path relPath) {
        int len = relPath.getNameCount();
        if(len == 0) {
            return this;
        } else {
            PathItem child = getRelChild(relPath.getName(0));
            if(child == null) {
                return null;
            } else if(len == 1) {
                return child;
            } else {
                return child.resolve(relPath.subpath(1, len));
            }
        }
    }
}

class FileItem extends PathItem {
    public static FileItem create(Path path, FileTime lastModified, GraphicFactory graphicFactory) {
        return new FileItem(path, lastModified, graphicFactory.create(path, false));
    }

    private FileTime lastModified;

    private FileItem(Path path, FileTime lastModified, Node graphic) {
        super(path, graphic);
        this.lastModified = lastModified;
    }

    @Override
    public final boolean isDirectory() {
        return false;
    }

    public boolean updateModificationTime(FileTime lastModified) {
        if(lastModified.compareTo(this.lastModified) > 0) {
            this.lastModified = lastModified;
            return true;
        } else {
            return false;
        }
    }
}

class DirItem extends PathItem {
    public static DirItem create(Path path, GraphicFactory graphicFactory) {
        return new DirItem(path, graphicFactory.create(path, true));
    }

    protected DirItem(Path path, Node graphic) {
        super(path, graphic);
    }

    @Override
    public final boolean isDirectory() {
        return true;
    }

    public FileItem addChildFile(Path fileName, FileTime lastModified, GraphicFactory graphicFactory) {
        assert fileName.getNameCount() == 1;
        int i = getFileInsertionIndex(fileName.toString());
        FileItem child = FileItem.create(getValue().resolve(fileName), lastModified, graphicFactory);
        getChildren().add(i, child);
        return child;
    }

    public DirItem addChildDir(Path dirName, GraphicFactory graphicFactory) {
        assert dirName.getNameCount() == 1;
        int i = getDirInsertionIndex(dirName.toString());
        DirItem child = DirItem.create(getValue().resolve(dirName), graphicFactory);
        getChildren().add(i, child);
        return child;
    }

    private int getFileInsertionIndex(String fileName) {
        ObservableList<TreeItem<Path>> children = getChildren();
        int n = children.size();
        for(int i = 0; i < n; ++i) {
            PathItem child = (PathItem) children.get(i);
            if(!child.isDirectory()) {
                String childName = child.getValue().getFileName().toString();
                if(childName.compareToIgnoreCase(fileName) > 0) {
                    return i;
                }
            }
        }
        return n;
    }

    private int getDirInsertionIndex(String dirName) {
        ObservableList<TreeItem<Path>> children = getChildren();
        int n = children.size();
        for(int i = 0; i < n; ++i) {
            PathItem child = (PathItem) children.get(i);
            if(child.isDirectory()) {
                String childName = child.getValue().getFileName().toString();
                if(childName.compareToIgnoreCase(dirName) > 0) {
                    return i;
                }
            } else {
                return i;
            }
        }
        return n;
    }
}

class ParentChild {
    private final DirItem parent;
    private final PathItem child;

    public ParentChild(DirItem parent, PathItem child) {
        this.parent = parent;
        this.child = child;
    }

    public DirItem getParent() { return parent; }
    public PathItem getChild() { return child; }
}

interface Reporter {
    void reportCreation(Path baseDir, Path relPath, Object origin);
    void reportDeletion(Path baseDir, Path relPath, Object origin);
    void reportModification(Path baseDir, Path relPath, Object origin);
    void reportError(Throwable error);
}

class TopLevelDirItem extends DirItem {
    private final GraphicFactory graphicFactory;
    private final Reporter reporter;

    TopLevelDirItem(Path path, GraphicFactory graphicFactory, Reporter reporter) {
        super(path, graphicFactory.create(path, true));
        this.graphicFactory = graphicFactory;
        this.reporter = reporter;
    }

    private ParentChild resolveInParent(Path relPath) {
        int len = relPath.getNameCount();
        if(len == 0) {
            return new ParentChild(null, this);
        } else if(len == 1) {
            if(getValue().resolve(relPath).equals(getValue())) {
                return new ParentChild(null, this);
            } else {
                return new ParentChild(this, getRelChild(relPath.getName(0)));
            }
        } else {
            PathItem parent = resolve(relPath.subpath(0, len - 1));
            if(parent == null || !parent.isDirectory()) {
                return new ParentChild(null, null);
            } else {
                PathItem child = parent.getRelChild(relPath.getFileName());
                return new ParentChild(parent.asDirItem(), child);
            }
        }
    }

    private void updateFile(Path relPath, FileTime lastModified, Object origin) {
        PathItem item = resolve(relPath);
        if(item == null || item.isDirectory()) {
            sync(PathNode.file(getValue().resolve(relPath), lastModified), origin);
        }
    }

    public void addFile(Path relPath, FileTime lastModified, Object origin) {
        updateFile(relPath, lastModified, origin);
    }

    public void updateModificationTime(Path relPath, FileTime lastModified, Object origin) {
        updateFile(relPath, lastModified, origin);
    }

    public void addDirectory(Path relPath, Object origin) {
        PathItem item = resolve(relPath);
        if(item == null || !item.isDirectory()) {
            sync(PathNode.directory(getValue().resolve(relPath), Collections.emptyList()), origin);
        }
    }

    public void sync(PathNode tree, Object origin) {
        Path path = tree.getPath();
        Path relPath = getValue().relativize(path);
        ParentChild pc = resolveInParent(relPath);
        DirItem parent = pc.getParent();
        PathItem item = pc.getChild();
        if(parent != null) {
            syncChild(parent, relPath.getFileName(), tree, origin);
        } else if(item == null) { // neither path nor its parent present in model
            raise(new NoSuchElementException("Parent directory for " + relPath + " does not exist within " + getValue()));
        } else { // resolved to top-level dir
            assert item == this;
            if(tree.isDirectory()) {
                syncContent(this, tree, origin);
            } else {
                raise(new IllegalArgumentException("Cannot replace top-level directory " + getValue() + " with a file"));
            }
        }
    }

    private void syncContent(DirItem dir, PathNode tree, Object origin) {
        Set<Path> desiredChildren = new HashSet<>();
        for(PathNode ch: tree.getChildren()) {
            desiredChildren.add(ch.getPath());
        }

        ArrayList<TreeItem<Path>> actualChildren = new ArrayList<>(dir.getChildren());

        // remove undesired children
        for(TreeItem<Path> ch: actualChildren) {
            if(!desiredChildren.contains(ch.getValue())) {
                removeNode(ch, null);
            }
        }

        // synchronize desired children
        for(PathNode ch: tree.getChildren()) {
            sync(ch, origin);
        }
    }

    private void syncChild(DirItem parent, Path childName, PathNode tree, Object origin) {
        PathItem child = parent.getRelChild(childName);
        if(child != null && child.isDirectory() != tree.isDirectory()) {
            removeNode(child, null);
        }
        if(child == null) {
            if(tree.isDirectory()) {
                DirItem dirChild = parent.addChildDir(childName, graphicFactory);
                reporter.reportCreation(getValue(), getValue().relativize(dirChild.getValue()), origin);
                syncContent(dirChild, tree, origin);
            } else {
                FileItem fileChild = parent.addChildFile(childName, tree.getLastModified(), graphicFactory);
                reporter.reportCreation(getValue(), getValue().relativize(fileChild.getValue()), origin);
            }
        } else {
            if(child.isDirectory()) {
                syncContent(child.asDirItem(), tree, origin);
            } else {
                if(child.asFileItem().updateModificationTime(tree.getLastModified())) {
                    reporter.reportModification(getValue(), getValue().relativize(child.getValue()), origin);
                }
            }
        }
    }

    public void remove(Path relPath, Object origin) {
        PathItem item = resolve(relPath);
        if(item != null) {
            removeNode(item, origin);
        }
    }

    private void removeNode(TreeItem<Path> node, Object origin) {
        signalDeletionRecursively(node, origin);
        node.getParent().getChildren().remove(node);
    }

    private void signalDeletionRecursively(TreeItem<Path> node, Object origin) {
        for(TreeItem<Path> child: node.getChildren()) {
            signalDeletionRecursively(child, origin);
        }
        reporter.reportDeletion(getValue(), getValue().relativize(node.getValue()), origin);
    }

    private void raise(Throwable t) {
        try {
            throw t;
        } catch(Throwable e) {
            reporter.reportError(e);
        }
    }
}