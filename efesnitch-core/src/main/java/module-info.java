import com.kohlschutter.efesnitch.core.PathWatcherImpl;

/**
 * efesnitch-core.
 */
module com.kohlschutter.efesnitch.core {
  exports com.kohlschutter.efesnitch.core;

  requires transitive com.kohlschutter.efesnitch;
  requires java.base;

  requires static com.kohlschutter.annotations.compiletime;
  requires static org.eclipse.jdt.annotation;
  requires org.slf4j;
  requires com.kohlschutter.util;

  provides com.kohlschutter.efesnitch.PathWatcher with PathWatcherImpl;
}
