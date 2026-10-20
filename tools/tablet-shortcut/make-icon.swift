import AppKit
import Foundation

let output = URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true)
try FileManager.default.createDirectory(at: output, withIntermediateDirectories: true)

func drawIcon(size: Int) throws {
    let bitmap = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: size, pixelsHigh: size, bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: size * 4, bitsPerPixel: 32)!
    bitmap.size = NSSize(width: size, height: size)
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: bitmap)
    let context = NSGraphicsContext.current!.cgContext
    context.scaleBy(x: CGFloat(size) / 1024, y: CGFloat(size) / 1024)

    let tile = NSBezierPath(roundedRect: NSRect(x: 60, y: 60, width: 904, height: 904), xRadius: 204, yRadius: 204)
    NSGradient(starting: NSColor(srgbRed: 0.12, green: 0.42, blue: 0.90, alpha: 1), ending: NSColor(srgbRed: 0.06, green: 0.24, blue: 0.62, alpha: 1))!.draw(in: tile, angle: -55)

    NSGraphicsContext.saveGraphicsState()
    let shadow = NSShadow()
    shadow.shadowColor = NSColor.black.withAlphaComponent(0.28)
    shadow.shadowBlurRadius = 35
    shadow.shadowOffset = NSSize(width: 0, height: -18)
    shadow.set()
    let body = NSBezierPath(roundedRect: NSRect(x: 166, y: 259, width: 692, height: 506), xRadius: 58, yRadius: 58)
    NSColor(srgbRed: 0.88, green: 0.92, blue: 0.97, alpha: 1).setFill()
    body.fill()
    NSGraphicsContext.restoreGraphicsState()

    let bezel = NSBezierPath(roundedRect: NSRect(x: 175, y: 268, width: 674, height: 488), xRadius: 49, yRadius: 49)
    NSColor(srgbRed: 0.08, green: 0.12, blue: 0.19, alpha: 1).setFill()
    bezel.fill()

    let screen = NSBezierPath(roundedRect: NSRect(x: 198, y: 291, width: 628, height: 442), xRadius: 30, yRadius: 30)
    NSGradient(starting: NSColor(srgbRed: 0.63, green: 0.89, blue: 1.0, alpha: 1), ending: NSColor(srgbRed: 0.26, green: 0.62, blue: 0.96, alpha: 1))!.draw(in: screen, angle: -50)
    NSGraphicsContext.saveGraphicsState()
    screen.addClip()
    NSColor(srgbRed: 0.20, green: 0.49, blue: 0.91, alpha: 1).setFill()
    NSBezierPath(ovalIn: NSRect(x: 458, y: 168, width: 470, height: 470)).fill()
    NSColor(srgbRed: 0.72, green: 0.95, blue: 0.94, alpha: 1).setFill()
    NSBezierPath(ovalIn: NSRect(x: 105, y: 165, width: 475, height: 475)).fill()
    NSGraphicsContext.restoreGraphicsState()

    NSColor(srgbRed: 0.27, green: 0.35, blue: 0.45, alpha: 1).setFill()
    NSBezierPath(ovalIn: NSRect(x: 507, y: 739, width: 10, height: 10)).fill()
    NSColor.white.withAlphaComponent(0.92).setFill()
    NSBezierPath(roundedRect: NSRect(x: 447, y: 307, width: 130, height: 7), xRadius: 4, yRadius: 4).fill()
    NSGraphicsContext.restoreGraphicsState()
    let data = bitmap.representation(using: .png, properties: [:])!
    for (name, pixelSize) in [
        ("icon_16x16.png", 16), ("icon_16x16@2x.png", 32),
        ("icon_32x32.png", 32), ("icon_32x32@2x.png", 64),
        ("icon_128x128.png", 128), ("icon_128x128@2x.png", 256),
        ("icon_256x256.png", 256), ("icon_256x256@2x.png", 512),
        ("icon_512x512.png", 512), ("icon_512x512@2x.png", 1024)
    ] where pixelSize == size {
        try data.write(to: output.appendingPathComponent(name))
    }
}

for size in [16, 32, 64, 128, 256, 512, 1024] {
    try drawIcon(size: size)
}
