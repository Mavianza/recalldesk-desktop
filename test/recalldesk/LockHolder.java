package recalldesk;

import java.nio.file.Path;

public final class LockHolder {
    private LockHolder() { }
    public static void main(String[] args) throws Exception {
        try (Store store = new Store(Path.of(args[0]))) {
            System.out.println("LOCKED " + store.path()); System.out.flush();
            System.in.read();
        }
    }
}
