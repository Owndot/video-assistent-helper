# ERZEUGT von :app:traceTestKeepRules (R8 TraceReferences) - nicht von Hand bearbeiten.
# Was die Instrumentierungs-APK aus dem Laufzeit-Klassenpfad der App nutzt; siehe app/build.gradle.
-keep @interface androidx.annotation.ChecksSdkIntAtLeast {
  public int api();
  public int extension();
}
-keep @interface androidx.annotation.GuardedBy {
  public java.lang.String value();
}
-keep @interface androidx.annotation.MainThread {
}
-keep @interface androidx.annotation.NonNull {
}
-keep @interface androidx.annotation.Nullable {
}
-keep @interface androidx.annotation.RequiresApi {
  public int value();
}
-keep enum androidx.annotation.RestrictTo$Scope {
  androidx.annotation.RestrictTo$Scope LIBRARY;
  androidx.annotation.RestrictTo$Scope LIBRARY_GROUP;
  androidx.annotation.RestrictTo$Scope LIBRARY_GROUP_PREFIX;
}
-keep @interface androidx.annotation.RestrictTo {
  public androidx.annotation.RestrictTo$Scope[] value();
}
-keep @interface androidx.annotation.VisibleForTesting {
}
-keep class androidx.tracing.Trace {
  public static void beginSection(java.lang.String);
  public static void endSection();
}
-keep @interface kotlin.BuilderInference {
}
-keep @interface kotlin.Deprecated {
  public kotlin.DeprecationLevel level();
  public java.lang.String message();
  public kotlin.ReplaceWith replaceWith();
}
-keep enum kotlin.DeprecationLevel {
  kotlin.DeprecationLevel ERROR;
  kotlin.DeprecationLevel HIDDEN;
  kotlin.DeprecationLevel WARNING;
}
-keep class kotlin.ExceptionsKt {
}
-keep class kotlin.ExceptionsKt__ExceptionsKt {
  public static void addSuppressed(java.lang.Throwable, java.lang.Throwable);
}
-keep @interface kotlin.ExperimentalStdlibApi {
}
-keep class kotlin.KotlinNothingValueException {
  public <init>();
}
-keep class kotlin.KotlinVersion {
  public boolean isAtLeast(int, int, int);
  kotlin.KotlinVersion CURRENT;
}
-keep interface kotlin.Lazy {
  public java.lang.Object getValue();
}
-keep class kotlin.LazyKt {
}
-keep class kotlin.LazyKt__LazyJVMKt {
  public static kotlin.Lazy lazy(kotlin.jvm.functions.Function0);
}
-keep class kotlin.NoWhenBranchMatchedException {
  public <init>();
}
-keep @interface kotlin.OverloadResolutionByLambdaReturnType {
}
-keep class kotlin.Pair {
  public java.lang.Object component1();
  public java.lang.Object component2();
  public java.lang.Object getFirst();
  public java.lang.Object getSecond();
}
-keep @interface kotlin.PublishedApi {
}
-keep enum kotlin.RequiresOptIn$Level {
  kotlin.RequiresOptIn$Level ERROR;
  kotlin.RequiresOptIn$Level WARNING;
}
-keep @interface kotlin.RequiresOptIn {
  public kotlin.RequiresOptIn$Level level();
  public java.lang.String message();
}
-keep class kotlin.Result$Companion {
}
-keep class kotlin.Result {
  public static java.lang.Object constructor-impl(java.lang.Object);
  public static java.lang.Throwable exceptionOrNull-impl(java.lang.Object);
  public static boolean isFailure-impl(java.lang.Object);
  public static boolean isSuccess-impl(java.lang.Object);
  kotlin.Result$Companion Companion;
}
-keep class kotlin.ResultKt {
  public static java.lang.Object createFailure(java.lang.Throwable);
  public static void throwOnFailure(java.lang.Object);
}
-keep class kotlin.TuplesKt {
  public static kotlin.Pair to(java.lang.Object, java.lang.Object);
}
-keep class kotlin.Unit {
  kotlin.Unit INSTANCE;
}
-keep enum kotlin.annotation.AnnotationRetention {
  kotlin.annotation.AnnotationRetention BINARY;
}
-keep enum kotlin.annotation.AnnotationTarget {
  kotlin.annotation.AnnotationTarget ANNOTATION_CLASS;
  kotlin.annotation.AnnotationTarget CLASS;
  kotlin.annotation.AnnotationTarget CONSTRUCTOR;
  kotlin.annotation.AnnotationTarget FIELD;
  kotlin.annotation.AnnotationTarget FUNCTION;
  kotlin.annotation.AnnotationTarget LOCAL_VARIABLE;
  kotlin.annotation.AnnotationTarget PROPERTY;
  kotlin.annotation.AnnotationTarget PROPERTY_GETTER;
  kotlin.annotation.AnnotationTarget PROPERTY_SETTER;
  kotlin.annotation.AnnotationTarget TYPEALIAS;
  kotlin.annotation.AnnotationTarget VALUE_PARAMETER;
}
-keep @interface kotlin.annotation.MustBeDocumented {
}
-keep @interface kotlin.annotation.Retention {
  public kotlin.annotation.AnnotationRetention value();
}
-keep @interface kotlin.annotation.Target {
  public kotlin.annotation.AnnotationTarget[] allowedTargets();
}
-keep class kotlin.collections.AbstractMutableMap {
  protected <init>();
  public java.util.Set getEntries();
  public java.util.Set getKeys();
  public int getSize();
  public java.lang.Object put(java.lang.Object, java.lang.Object);
}
-keep class kotlin.collections.AbstractMutableSet {
  protected <init>();
  public boolean add(java.lang.Object);
  public int getSize();
}
-keep class kotlin.collections.ArrayDeque {
  public <init>();
  public void addLast(java.lang.Object);
  public boolean isEmpty();
  public java.lang.Object removeFirstOrNull();
}
-keep class kotlin.collections.ArraysKt {
}
-keep class kotlin.collections.ArraysKt___ArraysJvmKt {
  public static java.lang.Object[] copyInto$default(java.lang.Object[], java.lang.Object[], int, int, int, int, java.lang.Object);
  public static void fill$default(java.lang.Object[], java.lang.Object, int, int, int, java.lang.Object);
}
-keep class kotlin.collections.ArraysKt___ArraysKt {
  public static java.lang.Iterable asIterable(java.lang.Object[]);
  public static java.lang.Object getOrNull(java.lang.Object[], int);
}
-keep class kotlin.collections.CollectionsKt {
}
-keep class kotlin.collections.CollectionsKt__CollectionsJVMKt {
  public static java.util.List build(java.util.List);
  public static java.util.List createListBuilder();
  public static java.util.List createListBuilder(int);
  public static java.util.List listOf(java.lang.Object);
}
-keep class kotlin.collections.CollectionsKt__CollectionsKt {
  public static java.util.List emptyList();
  public static java.util.List listOf(java.lang.Object[]);
}
-keep class kotlin.collections.CollectionsKt__IterablesKt {
  public static int collectionSizeOrDefault(java.lang.Iterable, int);
}
-keep class kotlin.collections.CollectionsKt__MutableCollectionsKt {
  public static boolean addAll(java.util.Collection, java.lang.Iterable);
}
-keep class kotlin.collections.CollectionsKt___CollectionsKt {
  public static kotlin.sequences.Sequence asSequence(java.lang.Iterable);
  public static java.lang.Object firstOrNull(java.util.List);
  public static java.lang.String joinToString$default(java.lang.Iterable, java.lang.CharSequence, java.lang.CharSequence, java.lang.CharSequence, int, java.lang.CharSequence, kotlin.jvm.functions.Function1, int, java.lang.Object);
  public static java.util.List plus(java.util.Collection, java.lang.Object);
  public static java.util.List toList(java.lang.Iterable);
  public static java.util.Set toSet(java.lang.Iterable);
}
-keep class kotlin.collections.IndexedValue {
  public <init>(int, java.lang.Object);
  public int getIndex();
  public java.lang.Object getValue();
}
-keep class kotlin.collections.IntIterator {
  public int nextInt();
}
-keep class kotlin.collections.LongIterator {
  public long nextLong();
}
-keep class kotlin.collections.MapsKt {
}
-keep class kotlin.collections.MapsKt__MapsJVMKt {
  public static int mapCapacity(int);
}
-keep class kotlin.comparisons.ComparisonsKt {
}
-keep class kotlin.comparisons.ComparisonsKt__ComparisonsKt {
  public static int compareValues(java.lang.Comparable, java.lang.Comparable);
}
-keep class kotlin.concurrent.ThreadsKt {
  public static java.lang.Thread thread$default(boolean, boolean, java.lang.ClassLoader, java.lang.String, int, kotlin.jvm.functions.Function0, int, java.lang.Object);
}
-keep class kotlin.coroutines.AbstractCoroutineContextElement {
  public <init>(kotlin.coroutines.CoroutineContext$Key);
  public kotlin.coroutines.CoroutineContext$Element get(kotlin.coroutines.CoroutineContext$Key);
  public kotlin.coroutines.CoroutineContext minusKey(kotlin.coroutines.CoroutineContext$Key);
  public kotlin.coroutines.CoroutineContext plus(kotlin.coroutines.CoroutineContext);
}
-keep class kotlin.coroutines.AbstractCoroutineContextKey {
  public <init>(kotlin.coroutines.CoroutineContext$Key, kotlin.jvm.functions.Function1);
}
-keep interface kotlin.coroutines.Continuation {
  public kotlin.coroutines.CoroutineContext getContext();
  public void resumeWith(java.lang.Object);
}
-keep class kotlin.coroutines.ContinuationInterceptor$DefaultImpls {
  public static kotlin.coroutines.CoroutineContext$Element get(kotlin.coroutines.ContinuationInterceptor, kotlin.coroutines.CoroutineContext$Key);
  public static kotlin.coroutines.CoroutineContext minusKey(kotlin.coroutines.ContinuationInterceptor, kotlin.coroutines.CoroutineContext$Key);
}
-keep class kotlin.coroutines.ContinuationInterceptor$Key {
}
-keep interface kotlin.coroutines.ContinuationInterceptor {
  public kotlin.coroutines.CoroutineContext$Element get(kotlin.coroutines.CoroutineContext$Key);
  public kotlin.coroutines.Continuation interceptContinuation(kotlin.coroutines.Continuation);
  public kotlin.coroutines.CoroutineContext minusKey(kotlin.coroutines.CoroutineContext$Key);
  public void releaseInterceptedContinuation(kotlin.coroutines.Continuation);
  kotlin.coroutines.ContinuationInterceptor$Key Key;
}
-keep class kotlin.coroutines.ContinuationKt {
  public static kotlin.coroutines.Continuation createCoroutine(kotlin.jvm.functions.Function1, kotlin.coroutines.Continuation);
  public static void startCoroutine(kotlin.jvm.functions.Function1, kotlin.coroutines.Continuation);
  public static void startCoroutine(kotlin.jvm.functions.Function2, java.lang.Object, kotlin.coroutines.Continuation);
}
-keep class kotlin.coroutines.CoroutineContext$Element$DefaultImpls {
  public static java.lang.Object fold(kotlin.coroutines.CoroutineContext$Element, java.lang.Object, kotlin.jvm.functions.Function2);
  public static kotlin.coroutines.CoroutineContext$Element get(kotlin.coroutines.CoroutineContext$Element, kotlin.coroutines.CoroutineContext$Key);
  public static kotlin.coroutines.CoroutineContext minusKey(kotlin.coroutines.CoroutineContext$Element, kotlin.coroutines.CoroutineContext$Key);
  public static kotlin.coroutines.CoroutineContext plus(kotlin.coroutines.CoroutineContext$Element, kotlin.coroutines.CoroutineContext);
}
-keep interface kotlin.coroutines.CoroutineContext$Element {
  public java.lang.Object fold(java.lang.Object, kotlin.jvm.functions.Function2);
  public kotlin.coroutines.CoroutineContext$Element get(kotlin.coroutines.CoroutineContext$Key);
  public kotlin.coroutines.CoroutineContext$Key getKey();
  public kotlin.coroutines.CoroutineContext minusKey(kotlin.coroutines.CoroutineContext$Key);
  public kotlin.coroutines.CoroutineContext plus(kotlin.coroutines.CoroutineContext);
}
-keep interface kotlin.coroutines.CoroutineContext$Key {
}
-keep interface kotlin.coroutines.CoroutineContext {
  public java.lang.Object fold(java.lang.Object, kotlin.jvm.functions.Function2);
  public kotlin.coroutines.CoroutineContext$Element get(kotlin.coroutines.CoroutineContext$Key);
  public kotlin.coroutines.CoroutineContext minusKey(kotlin.coroutines.CoroutineContext$Key);
  public kotlin.coroutines.CoroutineContext plus(kotlin.coroutines.CoroutineContext);
}
-keep class kotlin.coroutines.EmptyCoroutineContext {
  kotlin.coroutines.EmptyCoroutineContext INSTANCE;
}
-keep class kotlin.coroutines.intrinsics.IntrinsicsKt {
}
-keep class kotlin.coroutines.intrinsics.IntrinsicsKt__IntrinsicsJvmKt {
  public static kotlin.coroutines.Continuation createCoroutineUnintercepted(kotlin.jvm.functions.Function1, kotlin.coroutines.Continuation);
  public static kotlin.coroutines.Continuation createCoroutineUnintercepted(kotlin.jvm.functions.Function2, java.lang.Object, kotlin.coroutines.Continuation);
  public static kotlin.coroutines.Continuation intercepted(kotlin.coroutines.Continuation);
}
-keep class kotlin.coroutines.intrinsics.IntrinsicsKt__IntrinsicsKt {
  public static java.lang.Object getCOROUTINE_SUSPENDED();
}
-keep class kotlin.coroutines.jvm.internal.Boxing {
  public static java.lang.Boolean boxBoolean(boolean);
  public static java.lang.Integer boxInt(int);
  public static java.lang.Long boxLong(long);
}
-keep class kotlin.coroutines.jvm.internal.ContinuationImpl {
  public <init>(kotlin.coroutines.Continuation);
  public <init>(kotlin.coroutines.Continuation, kotlin.coroutines.CoroutineContext);
  public kotlin.coroutines.jvm.internal.CoroutineStackFrame getCallerFrame();
  public kotlin.coroutines.CoroutineContext getContext();
  public java.lang.StackTraceElement getStackTraceElement();
  protected java.lang.Object invokeSuspend(java.lang.Object);
  protected void releaseIntercepted();
}
-keep interface kotlin.coroutines.jvm.internal.CoroutineStackFrame {
  public kotlin.coroutines.jvm.internal.CoroutineStackFrame getCallerFrame();
  public java.lang.StackTraceElement getStackTraceElement();
}
-keep @interface kotlin.coroutines.jvm.internal.DebugMetadata {
  public java.lang.String c();
  public java.lang.String f();
  public int[] i();
  public int[] l();
  public java.lang.String m();
  public java.lang.String[] n();
  public java.lang.String[] s();
}
-keep class kotlin.coroutines.jvm.internal.DebugProbesKt {
  public static kotlin.coroutines.Continuation probeCoroutineCreated(kotlin.coroutines.Continuation);
  public static void probeCoroutineSuspended(kotlin.coroutines.Continuation);
}
-keep class kotlin.coroutines.jvm.internal.RestrictedSuspendLambda {
  public <init>(int, kotlin.coroutines.Continuation);
  public kotlin.coroutines.Continuation create(java.lang.Object, kotlin.coroutines.Continuation);
  protected java.lang.Object invokeSuspend(java.lang.Object);
}
-keep interface kotlin.coroutines.jvm.internal.SuspendFunction {
}
-keep class kotlin.coroutines.jvm.internal.SuspendLambda {
  public <init>(int, kotlin.coroutines.Continuation);
  public kotlin.coroutines.Continuation create(java.lang.Object, kotlin.coroutines.Continuation);
  public kotlin.coroutines.Continuation create(kotlin.coroutines.Continuation);
  protected java.lang.Object invokeSuspend(java.lang.Object);
}
-keep @interface kotlin.internal.InlineOnly {
}
-keep @interface kotlin.internal.LowPriorityInOverloadResolution {
}
-keep class kotlin.io.ByteStreamsKt {
  public static byte[] readBytes(java.io.InputStream);
}
-keep class kotlin.io.CloseableKt {
  public static void closeFinally(java.io.Closeable, java.lang.Throwable);
}
-keep class kotlin.jvm.JvmClassMappingKt {
  public static kotlin.reflect.KClass getKotlinClass(java.lang.Class);
}
-keep @interface kotlin.jvm.JvmField {
}
-keep @interface kotlin.jvm.JvmInline {
}
-keep @interface kotlin.jvm.JvmName {
  public java.lang.String name();
}
-keep @interface kotlin.jvm.JvmStatic {
}
-keep @interface kotlin.jvm.Volatile {
}
-keep interface kotlin.jvm.functions.Function0 {
  public java.lang.Object invoke();
}
-keep interface kotlin.jvm.functions.Function1 {
  public java.lang.Object invoke(java.lang.Object);
}
-keep interface kotlin.jvm.functions.Function2 {
  public java.lang.Object invoke(java.lang.Object, java.lang.Object);
}
-keep interface kotlin.jvm.functions.Function3 {
  public java.lang.Object invoke(java.lang.Object, java.lang.Object, java.lang.Object);
}
-keep interface kotlin.jvm.functions.Function4 {
  public java.lang.Object invoke(java.lang.Object, java.lang.Object, java.lang.Object, java.lang.Object);
}
-keep interface kotlin.jvm.functions.Function5 {
  public java.lang.Object invoke(java.lang.Object, java.lang.Object, java.lang.Object, java.lang.Object, java.lang.Object);
}
-keep interface kotlin.jvm.functions.Function6 {
  public java.lang.Object invoke(java.lang.Object, java.lang.Object, java.lang.Object, java.lang.Object, java.lang.Object, java.lang.Object);
}
-keep interface kotlin.jvm.functions.Function7 {
  public java.lang.Object invoke(java.lang.Object, java.lang.Object, java.lang.Object, java.lang.Object, java.lang.Object, java.lang.Object, java.lang.Object);
}
-keep class kotlin.jvm.internal.CallableReference {
  java.lang.Object receiver;
}
-keep class kotlin.jvm.internal.DefaultConstructorMarker {
}
-keep class kotlin.jvm.internal.FunctionReferenceImpl {
  public <init>(int, java.lang.Class, java.lang.String, java.lang.String, int);
  public <init>(int, java.lang.Object, java.lang.Class, java.lang.String, java.lang.String, int);
}
-keep class kotlin.jvm.internal.InlineMarker {
  public static void finallyEnd(int);
  public static void finallyStart(int);
  public static void mark(int);
}
-keep class kotlin.jvm.internal.Intrinsics {
  public static boolean areEqual(java.lang.Object, java.lang.Object);
  public static void needClassReification();
  public static void reifiedOperationMarker(int, java.lang.String);
  public static void throwNpe();
  public static void throwUninitializedPropertyAccessException(java.lang.String);
}
-keep class kotlin.jvm.internal.Lambda {
  public <init>(int);
}
-keep class kotlin.jvm.internal.PropertyReference0Impl {
  public <init>(java.lang.Object, java.lang.Class, java.lang.String, java.lang.String, int);
  public java.lang.Object get();
}
-keep class kotlin.jvm.internal.Ref$BooleanRef {
  public <init>();
  boolean element;
}
-keep class kotlin.jvm.internal.Ref$IntRef {
  public <init>();
  int element;
}
-keep class kotlin.jvm.internal.Ref$LongRef {
  public <init>();
  long element;
}
-keep class kotlin.jvm.internal.Ref$ObjectRef {
  public <init>();
  java.lang.Object element;
}
-keep class kotlin.jvm.internal.Reflection {
  public static kotlin.reflect.KClass getOrCreateKotlinClass(java.lang.Class);
}
-keep @interface kotlin.jvm.internal.SourceDebugExtension {
  public java.lang.String[] value();
}
-keep class kotlin.jvm.internal.StringCompanionObject {
  kotlin.jvm.internal.StringCompanionObject INSTANCE;
}
-keep class kotlin.jvm.internal.TypeIntrinsics {
  public static java.lang.Object beforeCheckcastToFunctionOfArity(java.lang.Object, int);
}
-keep interface kotlin.jvm.internal.markers.KMutableIterator {
}
-keep interface kotlin.jvm.internal.markers.KMutableMap$Entry {
}
-keep class kotlin.random.Random$Default {
  public int nextInt();
}
-keep class kotlin.random.Random {
  kotlin.random.Random$Default Default;
}
-keep class kotlin.ranges.IntRange {
}
-keep class kotlin.ranges.LongRange {
}
-keep class kotlin.ranges.RangesKt {
}
-keep class kotlin.ranges.RangesKt___RangesKt {
  public static int coerceAtLeast(int, int);
  public static long coerceAtLeast(long, long);
  public static long coerceAtMost(long, long);
  public static kotlin.ranges.IntRange until(int, int);
}
-keep interface kotlin.reflect.KClass {
  public java.lang.String getSimpleName();
  public boolean isInstance(java.lang.Object);
}
-keep interface kotlin.reflect.KFunction {
}
-keep interface kotlin.sequences.Sequence {
  public java.util.Iterator iterator();
}
-keep class kotlin.sequences.SequenceScope {
  public java.lang.Object yield(java.lang.Object, kotlin.coroutines.Continuation);
}
-keep class kotlin.sequences.SequencesKt {
}
-keep class kotlin.sequences.SequencesKt__SequenceBuilderKt {
  public static kotlin.sequences.Sequence sequence(kotlin.jvm.functions.Function2);
}
-keep class kotlin.sequences.SequencesKt__SequencesKt {
  public static kotlin.sequences.Sequence asSequence(java.util.Iterator);
  public static kotlin.sequences.Sequence emptySequence();
}
-keep class kotlin.sequences.SequencesKt___SequencesKt {
  public static kotlin.sequences.Sequence filter(kotlin.sequences.Sequence, kotlin.jvm.functions.Function1);
  public static kotlin.sequences.Sequence mapNotNull(kotlin.sequences.Sequence, kotlin.jvm.functions.Function1);
  public static kotlin.sequences.Sequence sortedWith(kotlin.sequences.Sequence, java.util.Comparator);
  public static java.util.List toList(kotlin.sequences.Sequence);
}
-keep class kotlin.text.StringsKt {
}
-keep class kotlin.text.StringsKt__IndentKt {
  public static java.lang.String trimIndent(java.lang.String);
}
-keep class kotlin.text.StringsKt__StringNumberConversionsKt {
  public static java.lang.Long toLongOrNull(java.lang.String);
}
-keep class kotlin.text.StringsKt__StringsJVMKt {
  public static boolean startsWith$default(java.lang.String, java.lang.String, boolean, int, java.lang.Object);
}
-keep class kotlin.text.StringsKt__StringsKt {
  public static int lastIndexOf$default(java.lang.CharSequence, java.lang.String, int, boolean, int, java.lang.Object);
  public static java.lang.String substringAfter$default(java.lang.String, java.lang.String, java.lang.String, int, java.lang.Object);
  public static java.lang.String substringBefore$default(java.lang.String, char, java.lang.String, int, java.lang.Object);
  public static java.lang.String substringBefore$default(java.lang.String, java.lang.String, java.lang.String, int, java.lang.Object);
  public static java.lang.CharSequence trim(java.lang.CharSequence);
}
-keep class kotlin.text.StringsKt___StringsKt {
  public static char last(java.lang.CharSequence);
}
-keep class kotlin.time.Duration$Companion {
  public long getINFINITE-UwyO8pc();
  public long getZERO-UwyO8pc();
}
-keep class kotlin.time.Duration {
  public static int compareTo-LRDsOJo(long, long);
  public static long getInWholeMilliseconds-impl(long);
  public static java.lang.String toString-impl(long);
  public long unbox-impl();
  kotlin.time.Duration$Companion Companion;
}
-keep class kotlin.time.DurationKt {
  public static long toDuration(int, kotlin.time.DurationUnit);
  public static long toDuration(long, kotlin.time.DurationUnit);
}
-keep enum kotlin.time.DurationUnit {
  kotlin.time.DurationUnit MILLISECONDS;
  kotlin.time.DurationUnit SECONDS;
}
-keep interface okhttp3.Call {
  public okhttp3.Response execute();
}
-keep class okhttp3.CipherSuite {
  public java.lang.String javaName();
}
-keep class okhttp3.Handshake {
  public okhttp3.CipherSuite cipherSuite();
  public java.util.List peerCertificates();
  public okhttp3.TlsVersion tlsVersion();
}
-keep class okhttp3.HttpUrl {
  public static okhttp3.HttpUrl get(java.lang.String);
  public java.lang.String topPrivateDomain();
}
-keep class okhttp3.OkHttp {
  java.lang.String VERSION;
}
-keep class okhttp3.OkHttpClient$Builder {
  public okhttp3.OkHttpClient build();
  public okhttp3.OkHttpClient$Builder hostnameVerifier(javax.net.ssl.HostnameVerifier);
  public okhttp3.OkHttpClient$Builder sslSocketFactory(javax.net.ssl.SSLSocketFactory, javax.net.ssl.X509TrustManager);
}
-keep class okhttp3.OkHttpClient {
  public okhttp3.OkHttpClient$Builder newBuilder();
  public okhttp3.Call newCall(okhttp3.Request);
}
-keep enum okhttp3.Protocol {
  okhttp3.Protocol HTTP_1_1;
}
-keep class okhttp3.Request$Builder {
  public <init>();
  public okhttp3.Request build();
  public okhttp3.Request$Builder url(java.lang.String);
}
-keep class okhttp3.Request {
}
-keep class okhttp3.Response {
  public okhttp3.ResponseBody body();
  public void close();
  public int code();
  public okhttp3.Handshake handshake();
  public okhttp3.Response priorResponse();
  public okhttp3.Protocol protocol();
}
-keep class okhttp3.ResponseBody {
  public java.io.InputStream byteStream();
  public java.lang.String string();
}
-keep enum okhttp3.TlsVersion {
  public java.lang.String javaName();
  okhttp3.TlsVersion TLS_1_2;
  okhttp3.TlsVersion TLS_1_3;
}
-keep @interface org.jetbrains.annotations.NotNull {
}
-keep @interface org.jetbrains.annotations.Nullable {
}
