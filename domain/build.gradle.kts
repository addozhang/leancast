plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // XmlPullParser API: provided by Android at runtime; kxml2 backs JVM tests only.
    compileOnly("net.sf.kxml:kxml2:2.3.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
}
