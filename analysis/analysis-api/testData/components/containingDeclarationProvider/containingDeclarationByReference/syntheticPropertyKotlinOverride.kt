// FILE: J.java
public abstract class J {
    public abstract Foo getFoo();
}

// FILE: Foo.java
public interface Foo {
}

// FILE: main.kt
fun main(c: J) {
    if (c is K) {
        c.fo<caret>o
    }
}

class K : J() {
    override fun getFoo(): Foo {
        TODO("Not yet implemented")
    }
}
