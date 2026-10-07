package org.dual.hexa.core.kernel.remote;

/** Una chiamata remota: puo' lanciare qualunque eccezione, e' {@link RemoteCaller} a tradurla. */
@FunctionalInterface
public interface ThrowingSupplier<T> {

    T get() throws Exception;
}
