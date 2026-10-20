import AppKit
import Foundation
import Darwin

private let avdName = "Pixel_Tablet"
private let sdk = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Android/sdk")
private let emulator = sdk.appendingPathComponent("emulator/emulator").path
private let adb = sdk.appendingPathComponent("platform-tools/adb").path

private struct LaunchError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

private func command(_ executable: String, _ arguments: [String], timeout: TimeInterval = 8) throws -> String {
    let process = Process()
    let pipe = Pipe()
    process.executableURL = URL(fileURLWithPath: executable)
    process.arguments = arguments
    process.standardOutput = pipe
    process.standardError = FileHandle.nullDevice
    try process.run()
    // Drain output before waiting, so a large process inventory cannot fill the pipe.
    let timeoutWork = DispatchWorkItem { if process.isRunning { process.terminate() } }
    DispatchQueue.global().asyncAfter(deadline: .now() + timeout, execute: timeoutWork)
    let data = pipe.fileHandleForReading.readDataToEndOfFile()
    process.waitUntilExit()
    timeoutWork.cancel()
    guard process.terminationStatus == 0 else {
        throw LaunchError(message: "No se pudo consultar o iniciar la tablet. Inténtalo de nuevo.")
    }
    return String(data: data, encoding: .utf8) ?? ""
}

private struct Instance {
    let pid: pid_t
    let hidden: Bool
}

private func existingInstance() throws -> Instance? {
    let processes = try command("/bin/ps", ["-axo", "pid=,command="])
    let expectedBinary = sdk.appendingPathComponent("emulator/qemu/darwin-aarch64/qemu-system-aarch64").path
    for line in processes.split(separator: "\n") {
        let tokens = line.split(whereSeparator: { $0.isWhitespace }).map(String.init)
        guard tokens.count >= 4, let pid = Int32(tokens[0]), tokens[1] == expectedBinary,
              let flag = tokens.firstIndex(of: "-avd"), flag + 1 < tokens.count,
              tokens[flag + 1] == avdName else { continue }
        return Instance(pid: pid, hidden: tokens.contains("-qt-hide-window") || tokens.contains("-no-window"))
    }
    return nil
}

private func serialForTablet() throws -> String? {
    let devices = try command(adb, ["devices"])
    for line in devices.split(separator: "\n") {
        let fields = line.split(whereSeparator: { $0.isWhitespace }).map(String.init)
        guard fields.count >= 2, fields[0].hasPrefix("emulator-"), fields[1] == "device" else { continue }
        if let name = try? command(adb, ["-s", fields[0], "emu", "avd", "name"]),
           name.components(separatedBy: .newlines).first?.trimmingCharacters(in: .whitespacesAndNewlines) == avdName {
            return fields[0]
        }
    }
    return nil
}

private func openTablet() throws -> pid_t {
    let files = FileManager.default
    guard files.isExecutableFile(atPath: emulator), files.isExecutableFile(atPath: adb) else {
        throw LaunchError(message: "No se encontró Android Emulator en la instalación de Android SDK.")
    }
    let avd = files.homeDirectoryForCurrentUser.appendingPathComponent(".android/avd/\(avdName).ini")
    guard files.fileExists(atPath: avd.path) else {
        throw LaunchError(message: "No se encontró Pixel Tablet. Créala de nuevo en Android Studio para usar este acceso directo.")
    }
    if let existing = try existingInstance() {
        if !existing.hidden { return existing.pid }
        // An IDE-embedded emulator has no console command to reveal its window.
        // Gracefully stop ONLY this AVD, preserving its normal snapshot/data,
        // before reopening it as a standalone window.
        guard let serial = try serialForTablet() else {
            throw LaunchError(message: "La tablet todavía está arrancando en Android Studio. Espera a que termine y abre este acceso directo otra vez.")
        }
        _ = try command(adb, ["-s", serial, "emu", "kill"], timeout: 15)
        let deadline = Date().addingTimeInterval(45)
        while Darwin.kill(existing.pid, 0) == 0 && Date() < deadline { Thread.sleep(forTimeInterval: 0.3) }
        guard Darwin.kill(existing.pid, 0) != 0 else {
            throw LaunchError(message: "La tablet sigue cerrándose. Espera unos segundos y vuelve a abrir el acceso directo.")
        }
    }
    let logFolder = files.homeDirectoryForCurrentUser.appendingPathComponent("Library/Logs/PixelTablet")
    try files.createDirectory(at: logFolder, withIntermediateDirectories: true)
    let logFile = logFolder.appendingPathComponent("emulator.log")
    if !files.fileExists(atPath: logFile.path) { files.createFile(atPath: logFile.path, contents: nil) }
    let log = try FileHandle(forWritingTo: logFile)
    try log.seekToEnd()
    try log.write(contentsOf: Data("\n--- Pixel Tablet \(Date()) ---\n".utf8))
    let process = Process()
    process.executableURL = URL(fileURLWithPath: emulator)
    // Keep full-resolution rendering on the Mac GPU. Auto can switch to the
    // much slower software renderer when available host memory dips briefly.
    process.arguments = ["-avd", avdName, "-adb-path", adb, "-gpu", "host"]
    process.standardInput = FileHandle.nullDevice
    process.standardOutput = log
    process.standardError = log
    var environment = ProcessInfo.processInfo.environment
    environment["ANDROID_SDK_ROOT"] = sdk.path
    process.environment = environment
    try process.run()
    Thread.sleep(forTimeInterval: 3)
    guard process.isRunning else {
        throw LaunchError(message: "El emulador no pudo arrancar. El registro está en Library/Logs/PixelTablet/emulator.log.")
    }
    return process.processIdentifier
}

private final class Delegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        DispatchQueue.global(qos: .userInitiated).async {
            do {
                let pid = try openTablet()
                DispatchQueue.main.async {
                    NSRunningApplication(processIdentifier: pid)?.activate(options: [.activateAllWindows])
                    NSApplication.shared.terminate(nil)
                }
            } catch {
                DispatchQueue.main.async {
                    NSApplication.shared.activate(ignoringOtherApps: true)
                    let alert = NSAlert()
                    alert.messageText = "Pixel Tablet"
                    alert.informativeText = error.localizedDescription
                    alert.addButton(withTitle: "Aceptar")
                    alert.runModal()
                    NSApplication.shared.terminate(nil)
                }
            }
        }
    }
}

let application = NSApplication.shared
private let delegate = Delegate()
application.delegate = delegate
application.setActivationPolicy(.accessory)
application.run()
