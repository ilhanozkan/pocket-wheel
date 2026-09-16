import SwiftUI
import AppKit
import PocketWheelCore
import Darwin

/*
 THESIS: A native control station makes pairing and input state immediately legible.
 OWN-WORLD: System materials and typography, blue primary actions, native grouped forms.
 STORY: Start the receiver, pair the phone, enable controls, then arm on the phone.
 FIRST VIEWPORT: Receiver action at top; pairing details, enable switch, live inputs below.
 FORM: The pinned macOS Operate direction in DESIGN.md determines the composition.
 */

@MainActor
final class CompanionModel: ObservableObject {
    @Published var snapshot = BridgeSnapshot()
    @Published var pairingKey: String
    @Published var addresses = LocalNetwork.ipv4Addresses()
    @Published var revealKey = false
    private var bridge: UDPBridge!
    private var workspaceObservers: [NSObjectProtocol] = []

    init() {
        let stored = UserDefaults.standard.string(forKey: "pairingKey") ?? ""
        pairingKey = Wire.isIdentifier(stored) ? stored : Wire.randomIdentifier()
        UserDefaults.standard.set(pairingKey, forKey: "pairingKey")
        bridge = UDPBridge(diagnosticsURL: BridgeDiagnostics.defaultFileURL) { [weak self] value in
            DispatchQueue.main.async { self?.snapshot = value }
        }
        // Sleep/wake always revokes desktop permission. The user enables and arms again.
        for name in [NSWorkspace.willSleepNotification, NSWorkspace.didWakeNotification] {
            workspaceObservers.append(NSWorkspace.shared.notificationCenter.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                Task { @MainActor in self?.bridge.setEnabled(false) }
            })
        }
    }

    func start() { addresses = LocalNetwork.ipv4Addresses(); bridge.start(key: pairingKey) }
    func stop() { bridge.stop() }
    func terminate() { bridge.stopAndWait() }
    func enable(_ value: Bool) { bridge.setEnabled(value) }
    func refreshAddresses() { addresses = LocalNetwork.ipv4Addresses() }
    func regenerateKey() {
        guard !snapshot.running else { return }
        pairingKey = Wire.randomIdentifier()
        UserDefaults.standard.set(pairingKey, forKey: "pairingKey")
    }
    func copyKey() { NSPasteboard.general.clearContents(); NSPasteboard.general.setString(pairingKey, forType: .string) }
}

struct CompanionView: View {
    @ObservedObject var model: CompanionModel
    private var state: BridgeSnapshot { model.snapshot }
    private var status: String {
        if !state.running { return "Receiver stopped" }
        if state.controls.armed { return "Controls active" }
        if state.connected { return "Phone connected · controls disarmed" }
        if state.phoneAddress != nil { return "Phone signal lost · controls disarmed" }
        return "Waiting for your phone"
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack(alignment: .center) {
                VStack(alignment: .leading, spacing: 5) {
                    Text("Pocket Wheel").font(.title2.weight(.semibold))
                    Label(status, systemImage: state.controls.armed ? "steeringwheel" : (state.connected ? "iphone.radiowaves.left.and.right" : "wifi"))
                        .foregroundStyle(state.controls.armed ? Color.green : Color.secondary)
                    Text("ETS2 plugin: not verified").font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                Button(state.running ? "Stop receiver" : "Start receiver") {
                    if state.running { model.stop() } else { model.start() }
                }
                .keyboardShortcut(.return, modifiers: [])
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
            }
            .padding(24)
            Divider()
            Form {
                Section {
                    if model.addresses.isEmpty {
                        Text("Connect this Mac to the same Wi-Fi as your phone, then refresh addresses.")
                            .foregroundStyle(.secondary)
                    } else {
                        LabeledContent("Mac address") {
                            VStack(alignment: .trailing, spacing: 4) {
                                ForEach(model.addresses, id: \.self) { address in
                                    Text(address).font(.system(.body, design: .monospaced)).textSelection(.enabled)
                                }
                            }
                        }
                    }
                    LabeledContent("UDP port", value: "26760")
                    HStack {
                        Text("Pairing key")
                        Spacer()
                        HStack {
                            Text(model.revealKey ? model.pairingKey : "•••• •••• •••• ••••")
                                .font(.system(.body, design: .monospaced))
                                .textSelection(.enabled)
                            Button(model.revealKey ? "Hide" : "Show") { model.revealKey.toggle() }
                                .accessibilityLabel(model.revealKey ? "Hide pairing key" : "Show pairing key")
                            Button("Copy") { model.copyKey() }.accessibilityLabel("Copy pairing key")
                        }
                    }
                    HStack {
                        Button("Refresh addresses") { model.refreshAddresses() }
                        Spacer()
                        Button("New pairing key") { model.regenerateKey() }.disabled(state.running)
                            .help("Stop the receiver before changing the pairing key.")
                    }
                } header: {
                    Text("Pair your Android phone")
                } footer: {
                    Text("Enter this Mac’s address, port, and key in the phone app. Keep both devices on the same Wi-Fi.")
                }

                Section {
                    Toggle("Enable controls", isOn: Binding(get: { state.enabled }, set: model.enable))
                        .disabled(!state.running)
                    Text(state.enabled ? "Tap Arm on your phone to start driving. After signal loss, rearm on the phone." : "Enable here, then tap Arm on the phone. Both steps are required.")
                        .foregroundStyle(.secondary)
                } header: { Text("Driving permission") }

                Section {
                    AxisView(title: "Steering", value: state.controls.steering, bipolar: true, tint: .accentColor)
                    AxisView(title: "Gas", value: state.controls.throttle, tint: .blue)
                    AxisView(title: "Brake", value: state.controls.brake, tint: .red)
                    LabeledContent("Buttons", value: buttonDescription)
                } header: { Text("Output to game plugin") } footer: {
                    Text("ETS2 integration is unverified here. Install the plugin and bind its controls inside the game. Gear buttons report requests, not the current gear.")
                }
                Section {
                    LabeledContent("Largest receive gap", value: gapText(state.maximumReceiveGapMilliseconds))
                    LabeledContent("Largest output timer gap", value: gapText(state.maximumTickGapMilliseconds))
                } header: { Text("Connection diagnostics") } footer: {
                    Text("Since the receiver started. Large gaps in both can indicate a Mac scheduling stall. A receive gap alone can indicate phone or network delays. Values update when the next frame or timer tick arrives.")
                }
                if let error = state.error {
                    Section { Label(error, systemImage: "exclamationmark.triangle").foregroundStyle(.red).textSelection(.enabled) }
                }
            }
            .formStyle(.grouped)
        }
        .frame(minWidth: 570, idealWidth: 620, minHeight: 700, idealHeight: 810)
    }

    private var buttonDescription: String {
        let names = ["Drive", "Neutral", "Reverse", "Shift up", "Shift down", "Parking brake"]
        let pressed = names.enumerated().filter { state.controls.buttons & (1 << $0.offset) != 0 }.map(\.element)
        return pressed.isEmpty ? "Released" : pressed.joined(separator: ", ")
    }

    private func gapText(_ milliseconds: Double) -> String {
        milliseconds > 0 ? String(format: "%.0f ms", milliseconds) : "Waiting for samples"
    }
}

struct AxisView: View {
    let title: String
    let value: Int
    var bipolar = false
    let tint: Color
    var body: some View {
        HStack(spacing: 16) {
            Text(title).frame(width: 65, alignment: .leading)
            GeometryReader { geometry in
                ZStack(alignment: .leading) {
                    Capsule().fill(Color.secondary.opacity(0.15))
                    if bipolar {
                        Capsule().fill(tint)
                            .frame(width: max(2, geometry.size.width * CGFloat(abs(value)) / 20000))
                            .offset(x: value < 0 ? geometry.size.width * (0.5 + CGFloat(value) / 20000) : geometry.size.width / 2)
                        Rectangle().fill(Color.primary.opacity(0.65)).frame(width: 1)
                            .offset(x: geometry.size.width / 2)
                    } else {
                        Capsule().fill(tint).frame(width: geometry.size.width * CGFloat(value) / 10000)
                    }
                }
            }.frame(height: 8).accessibilityHidden(true)
            Text("\(value / 100)%").monospacedDigit().frame(width: 56, alignment: .trailing)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(title).accessibilityValue("\(value / 100) percent")
    }
}

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    var model: CompanionModel?
    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApplication.shared.setActivationPolicy(.regular)
        NSApplication.shared.activate(ignoringOtherApps: true)
    }
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { true }
    func applicationWillTerminate(_ notification: Notification) { model?.terminate() }
}

struct PocketWheelDesktop: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @StateObject private var model = CompanionModel()
    var body: some Scene {
        WindowGroup {
            CompanionView(model: model).onAppear { delegate.model = model }
        }
        .defaultSize(width: 620, height: 810)
        .commands { CommandGroup(replacing: .newItem) {} }
    }
}

@main
enum EntryPoint {
    @MainActor static func main() {
        if CommandLine.arguments.contains("--headless") { runHeadless() }
        else { PocketWheelDesktop.main() }
    }

    static func runHeadless() {
        let arguments = CommandLine.arguments
        func option(_ name: String) -> String? {
            guard let index = arguments.firstIndex(of: name), index + 1 < arguments.count else { return nil }
            return arguments[index + 1]
        }
        guard let key = option("--key"), Wire.isIdentifier(key) else {
            fputs("Headless mode requires --key with 16 lowercase hexadecimal characters.\n", stderr); exit(2)
        }
        let rawPort = option("--port") ?? "26760"
        guard let port = UInt16(rawPort), port > 0 else { fputs("Invalid UDP port.\n", stderr); exit(2) }
        var duration: Double?
        if let raw = option("--duration") {
            guard let seconds = Double(raw), seconds.isFinite, seconds > 0 else { fputs("Invalid duration.\n", stderr); exit(2) }
            duration = seconds
        }
        let bridge = UDPBridge { snapshot in
            if let error = snapshot.error { fputs("\(error)\n", stderr); exit(1) }
        }
        bridge.start(key: key, port: port, enabled: arguments.contains("--enable"))
        print("Pocket Wheel headless receiver: UDP \(port). Controls \(arguments.contains("--enable") ? "enabled" : "disabled").")
        signal(SIGINT, SIG_IGN); signal(SIGTERM, SIG_IGN)
        let signalSources = [SIGINT, SIGTERM].map { number -> DispatchSourceSignal in
            let source = DispatchSource.makeSignalSource(signal: number, queue: .main)
            source.setEventHandler { bridge.stopAndWait(); exit(0) }; source.resume(); return source
        }
        if let duration {
            DispatchQueue.main.asyncAfter(deadline: .now() + duration) { bridge.stopAndWait(); exit(0) }
        }
        withExtendedLifetime(signalSources) { dispatchMain() }
    }
}
