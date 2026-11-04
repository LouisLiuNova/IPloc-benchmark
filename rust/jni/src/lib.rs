//! JNI 边界不暴露裸指针；每次调用先取得 Arc，再释放注册表锁。
use iprange_core::{Algorithm, Snapshot, MAX_BATCH};
use jni::{
    objects::{JClass, JIntArray, JLongArray, JObject, JString, JValue},
    sys::{jint, jlong, jobject},
    JNIEnv,
};
use std::{
    collections::HashMap,
    panic::{catch_unwind, AssertUnwindSafe},
    path::Path,
    sync::{Arc, Mutex, OnceLock},
};

#[derive(Debug)]
struct Error {
    class: &'static str,
    message: String,
}
type Result<T> = std::result::Result<T, Error>;
fn error(class: &'static str, message: impl Into<String>) -> Error {
    Error {
        class,
        message: message.into(),
    }
}
impl From<jni::errors::Error> for Error {
    fn from(e: jni::errors::Error) -> Self {
        error("java/lang/IllegalStateException", e.to_string())
    }
}
#[derive(Default)]
struct Registry {
    next: i64,
    entries: HashMap<i64, Arc<Snapshot>>,
}
static REGISTRY: OnceLock<Mutex<Registry>> = OnceLock::new();
fn registry() -> &'static Mutex<Registry> {
    REGISTRY.get_or_init(|| Mutex::new(Registry::default()))
}
fn register(snapshot: Snapshot) -> Result<i64> {
    let mut r = registry()
        .lock()
        .map_err(|_| error("java/lang/IllegalStateException", "Registry poisoned"))?;
    r.next = r
        .next
        .checked_add(1)
        .ok_or_else(|| error("java/lang/IllegalStateException", "Handle space exhausted"))?;
    let id = r.next;
    r.entries.insert(id, Arc::new(snapshot));
    Ok(id)
}
fn acquire(id: i64) -> Result<Arc<Snapshot>> {
    registry()
        .lock()
        .map_err(|_| error("java/lang/IllegalStateException", "Registry poisoned"))?
        .entries
        .get(&id)
        .cloned()
        .ok_or_else(|| {
            error(
                "java/lang/IllegalStateException",
                "Snapshot closed or invalid handle",
            )
        })
}
fn close(id: i64) -> Result<()> {
    registry()
        .lock()
        .map_err(|_| error("java/lang/IllegalStateException", "Registry poisoned"))?
        .entries
        .remove(&id);
    Ok(())
}
fn boundary<T: Default>(env: &mut JNIEnv, f: impl FnOnce(&mut JNIEnv) -> Result<T>) -> T {
    let result = catch_unwind(AssertUnwindSafe(|| f(env)));
    match result {
        Ok(Ok(v)) => v,
        other => {
            let e = match other {
                Ok(Err(e)) => e,
                Err(_) => error("java/lang/IllegalStateException", "Native panic contained"),
                _ => unreachable!(),
            };
            if !env.exception_check().unwrap_or(true) {
                let _ = env.throw_new(e.class, e.message);
            }
            T::default()
        }
    }
}
fn range(
    env: &JNIEnv,
    array: &jni::objects::JPrimitiveArray<impl jni::objects::TypeArray>,
    offset: jint,
    length: jint,
) -> Result<()> {
    if array.is_null() {
        return Err(error("java/lang/NullPointerException", "Null array"));
    }
    let capacity = env.get_array_length(array)?;
    if offset < 0 || length < 0 || offset > capacity || length > capacity - offset {
        return Err(error(
            "java/lang/IndexOutOfBoundsException",
            "Invalid offset/length",
        ));
    }
    if length as usize > MAX_BATCH {
        return Err(error(
            "java/lang/IllegalArgumentException",
            "Batch exceeds 1000000",
        ));
    }
    Ok(())
}
fn algorithm(env: &mut JNIEnv, value: JString) -> Result<Algorithm> {
    let id: String = env.get_string(&value)?.into();
    Algorithm::parse(&id).map_err(|e| error("java/lang/IllegalArgumentException", e))
}
#[no_mangle]
pub extern "system" fn Java_io_iploc_rust_NativeBridge_abiVersion(
    mut env: JNIEnv,
    _: JClass,
) -> jint {
    boundary(&mut env, |_| Ok(1))
}
#[no_mangle]
pub extern "system" fn Java_io_iploc_rust_NativeBridge_open(
    mut env: JNIEnv,
    _: JClass,
    path: JString,
    alg: JString,
) -> jlong {
    boundary(&mut env, |env| {
        let alg = algorithm(env, alg)?;
        let path: String = env.get_string(&path)?.into();
        register(
            Snapshot::open(Path::new(&path), alg).map_err(|e| error("java/io/IOException", e))?,
        )
    })
}
#[no_mangle]
pub extern "system" fn Java_io_iploc_rust_NativeBridge_synthetic(
    mut env: JNIEnv,
    _: JClass,
    count: jint,
    alg: JString,
) -> jlong {
    boundary(&mut env, |env| {
        let alg = algorithm(env, alg)?;
        let count = usize::try_from(count)
            .map_err(|_| error("java/lang/IllegalArgumentException", "Synthetic row count"))?;
        register(
            Snapshot::synthetic(count, alg)
                .map_err(|e| error("java/lang/IllegalArgumentException", e))?,
        )
    })
}
#[no_mangle]
pub extern "system" fn Java_io_iploc_rust_NativeBridge_view(
    mut env: JNIEnv,
    _: JClass,
    id: jlong,
    alg: JString,
) -> jlong {
    boundary(&mut env, |env| {
        let snapshot = acquire(id)?;
        register(snapshot.with_algorithm(algorithm(env, alg)?))
    })
}
#[no_mangle]
pub extern "system" fn Java_io_iploc_rust_NativeBridge_close(
    mut env: JNIEnv,
    _: JClass,
    id: jlong,
) {
    boundary(&mut env, |_| close(id));
}
#[no_mangle]
pub extern "system" fn Java_io_iploc_rust_NativeBridge_version(
    mut env: JNIEnv,
    _: JClass,
    id: jlong,
) -> jobject {
    boundary(&mut env, |env| {
        Ok(env.new_string(acquire(id)?.version())?.into_raw())
    })
}
#[no_mangle]
pub extern "system" fn Java_io_iploc_rust_NativeBridge_rowCount(
    mut env: JNIEnv,
    _: JClass,
    id: jlong,
) -> jlong {
    boundary(&mut env, |_| Ok(acquire(id)?.len() as i64))
}
#[no_mangle]
pub extern "system" fn Java_io_iploc_rust_NativeBridge_indexBytes(
    mut env: JNIEnv,
    _: JClass,
    id: jlong,
) -> jlong {
    boundary(&mut env, |_| Ok(acquire(id)?.index_bytes() as i64))
}
#[no_mangle]
pub extern "system" fn Java_io_iploc_rust_NativeBridge_lookup(
    mut env: JNIEnv,
    _: JClass,
    id: jlong,
    ips: JIntArray,
    offset: jint,
    length: jint,
    rows: JLongArray,
    out: jint,
) {
    boundary(&mut env, |env| {
        let snapshot = acquire(id)?;
        range(env, &ips, offset, length)?;
        range(env, &rows, out, length)?;
        let mut input = vec![0i32; length as usize];
        env.get_int_array_region(&ips, offset, &mut input)?;
        let input: Vec<u32> = input.into_iter().map(|v| v as u32).collect();
        let mut output = vec![0i64; length as usize];
        snapshot.lookup(&input, &mut output);
        env.set_long_array_region(&rows, out, &output)?;
        Ok(())
    });
}
fn bitset<'a>(env: &mut JNIEnv<'a>, bytes: &[u8]) -> Result<JObject<'a>> {
    let array = env.byte_array_from_slice(bytes)?;
    Ok(env
        .call_static_method(
            "java/util/BitSet",
            "valueOf",
            "([B)Ljava/util/BitSet;",
            &[JValue::Object(array.as_ref())],
        )?
        .l()?)
}
#[no_mangle]
pub extern "system" fn Java_io_iploc_rust_NativeBridge_fetch(
    mut env: JNIEnv,
    _: JClass,
    id: jlong,
    rows: JLongArray,
    offset: jint,
    length: jint,
) -> jobject {
    boundary(&mut env, |env| {
        let snapshot = acquire(id)?;
        range(env, &rows, offset, length)?;
        let mut input = vec![0i64; length as usize];
        env.get_long_array_region(&rows, offset, &mut input)?;
        let payload = snapshot
            .fetch(&input)
            .map_err(|e| error("java/lang/IllegalArgumentException", e))?;
        // 全批仅固定数量 Java 对象；容量在 core 检查，不跨调用保留 JNI 引用。
        env.with_local_frame_returning_local(64, |env| -> Result<JObject> {
            let columns =
                env.new_object_array(8, "io/iploc/api/PayloadBatch$Column", JObject::null())?;
            for (f, column) in payload.columns.iter().enumerate() {
                let data = env.byte_array_from_slice(&column.data)?;
                let offsets = env.new_int_array(length + 1)?;
                env.set_int_array_region(&offsets, 0, &column.offsets)?;
                let valid = bitset(env, &column.valid)?;
                let object = env.new_object(
                    "io/iploc/api/PayloadBatch$Column",
                    "([B[ILjava/util/BitSet;)V",
                    &[
                        JValue::Object(data.as_ref()),
                        JValue::Object(offsets.as_ref()),
                        JValue::Object(&valid),
                    ],
                )?;
                env.set_object_array_element(&columns, f as i32, object)?;
            }
            let version = env.new_string(snapshot.version())?;
            let matched = bitset(env, &payload.matched)?;
            let object = env.new_object(
                "io/iploc/api/PayloadBatch",
                "(Ljava/lang/String;ILjava/util/BitSet;[Lio/iploc/api/PayloadBatch$Column;)V",
                &[
                    JValue::Object(version.as_ref()),
                    JValue::Int(length),
                    JValue::Object(&matched),
                    JValue::Object(columns.as_ref()),
                ],
            )?;
            Ok(object)
        })
        .map(|object| object.into_raw())
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn lifecycle_and_panic_cleanup() {
        let s = Snapshot::synthetic(8, Algorithm::Binary).unwrap();
        let id = register(s).unwrap();
        let acquired = acquire(id).unwrap();
        let barrier = Arc::new(std::sync::Barrier::new(2));
        let ready = barrier.clone();
        let task = std::thread::spawn(move || {
            ready.wait();
            ready.wait();
            let mut out = [0];
            acquired.lookup(&[256], &mut out);
            assert_eq!(out, [1]);
        });
        barrier.wait();
        close(id).unwrap();
        assert!(acquire(id).is_err());
        close(id).unwrap();
        barrier.wait();
        task.join().unwrap();
        for _ in 0..100 {
            let s = Arc::new(Snapshot::synthetic(4, Algorithm::Binary).unwrap());
            let weak = Arc::downgrade(&s);
            let id = register(s.with_algorithm(Algorithm::Binary)).unwrap();
            let acquired = acquire(id).unwrap();
            let live = Arc::downgrade(&acquired);
            drop(acquired);
            close(id).unwrap();
            assert!(live.upgrade().is_none());
            drop(s);
            assert!(weak.upgrade().is_none());
        }
        let id = register(Snapshot::synthetic(1, Algorithm::Binary).unwrap()).unwrap();
        assert!(catch_unwind(|| {
            let _lease = acquire(id).unwrap();
            panic!("controlled panic");
        })
        .is_err());
        close(id).unwrap();
        assert!(acquire(id).is_err());
        assert!(registry().lock().unwrap().entries.is_empty());
    }
}
