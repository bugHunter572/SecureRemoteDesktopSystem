// Draws the client app icon (the same mouse-with-signal glyph as computer-mouse.ico) as a crisp
// macOS iconset at every size and turns it into srd-client/packaging/macos/AppIcon.icns.
// The .ico only goes up to 256 px and its lines are transparent cut-outs, which looked blurry and
// dark in Finder/Launchpad; here everything is vector, the lines are white, and the round badge
// keeps the standard macOS icon margin so it sits at the same size as other apps.
//
// Run on a Mac:  swift build/make_macos_icon.swift
import AppKit
import CoreGraphics

let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
let out = root.appendingPathComponent("srd-client/packaging/macos/AppIcon.icns")
let iconset = FileManager.default.temporaryDirectory.appendingPathComponent("SRDAppIcon.iconset")

func render(_ px: Int) -> Data {
    let cs = CGColorSpace(name: CGColorSpace.sRGB)!
    let ctx = CGContext(data: nil, width: px, height: px, bitsPerComponent: 8, bytesPerRow: 0, space: cs,
                        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    let s = CGFloat(px) / 1024.0
    // Work in a 1024-unit, top-left origin space.
    ctx.translateBy(x: 0, y: CGFloat(px))
    ctx.scaleBy(x: s, y: -s)

    // Badge: circle on the macOS icon grid (about 824 of 1024) with a soft drop shadow.
    let badge = CGRect(x: 100, y: 92, width: 824, height: 824)
    ctx.saveGState()
    ctx.setShadow(offset: CGSize(width: 0, height: -10), blur: 24,
                  color: CGColor(srgbRed: 0, green: 0, blue: 0, alpha: 0.35))
    ctx.addEllipse(in: badge)
    ctx.setFillColor(CGColor(srgbRed: 0, green: 0.18, blue: 0.3, alpha: 1))
    ctx.fillPath()
    ctx.restoreGState()

    ctx.saveGState()
    ctx.addEllipse(in: badge)
    ctx.clip()
    let grad = CGGradient(colorsSpace: cs, colors: [
        CGColor(srgbRed: 0, green: 0.69, blue: 0.86, alpha: 1),   // top right  #00B0DB
        CGColor(srgbRed: 0, green: 0.45, blue: 0.60, alpha: 1),
        CGColor(srgbRed: 0, green: 0.11, blue: 0.23, alpha: 1),   // bottom left #001C3A
    ] as CFArray, locations: [0, 0.5, 1])!
    ctx.drawLinearGradient(grad, start: CGPoint(x: badge.maxX, y: badge.minY),
                           end: CGPoint(x: badge.minX, y: badge.maxY), options: [])
    ctx.restoreGState()

    // Glyph, from the .ico geometry (256 px space -> badge space).
    let k = badge.width / 256.0
    func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: badge.minX + x * k, y: badge.minY + y * k) }
    ctx.setStrokeColor(CGColor(srgbRed: 1, green: 1, blue: 1, alpha: 1))
    ctx.setFillColor(CGColor(srgbRed: 1, green: 1, blue: 1, alpha: 1))
    ctx.setLineWidth(7 * k)
    ctx.setLineCap(.round)
    ctx.setLineJoin(.round)

    // Mouse body: flat-ish top corners, fully rounded bottom.
    let l: CGFloat = 91.5, r: CGFloat = 163.5, t: CGFloat = 87.5, b: CGFloat = 198.5
    let rt: CGFloat = 20, rb: CGFloat = 36
    let body = CGMutablePath()
    body.move(to: p(l + rt, t))
    body.addLine(to: p(r - rt, t))
    body.addQuadCurve(to: p(r, t + rt), control: p(r, t))
    body.addLine(to: p(r, b - rb))
    body.addCurve(to: p((l + r) / 2, b), control1: p(r, b - rb * 0.45), control2: p((l + r) / 2 + rb * 0.55, b))
    body.addCurve(to: p(l, b - rb), control1: p((l + r) / 2 - rb * 0.55, b), control2: p(l, b - rb * 0.45))
    body.addLine(to: p(l, t + rt))
    body.addQuadCurve(to: p(l + rt, t), control: p(l, t))
    body.closeSubpath()
    ctx.addPath(body)
    ctx.strokePath()

    // Buttons: vertical split and the line under them.
    ctx.move(to: p(127.5, t)); ctx.addLine(to: p(127.5, 126))
    ctx.move(to: p(l, 126)); ctx.addLine(to: p(r, 126))
    ctx.strokePath()
    // Scroll wheel.
    ctx.setLineWidth(7.5 * k)
    ctx.move(to: p(127.5, 151)); ctx.addLine(to: p(127.5, 167))
    ctx.strokePath()

    // Signal: dot plus two arcs above the mouse.
    ctx.addEllipse(in: CGRect(origin: p(123.5, 72), size: CGSize(width: 8 * k, height: 8 * k)))
    ctx.fillPath()
    ctx.setLineWidth(6.5 * k)
    let c = p(127.5, 78)
    for radius in [13.0, 24.5] as [CGFloat] {
        ctx.addArc(center: c, radius: radius * k, startAngle: .pi * 1.22, endAngle: .pi * 1.78, clockwise: false)
        ctx.strokePath()
    }

    let img = ctx.makeImage()!
    let rep = NSBitmapImageRep(cgImage: img)
    rep.size = NSSize(width: px, height: px)
    return rep.representation(using: .png, properties: [:])!
}

try? FileManager.default.removeItem(at: iconset)
try FileManager.default.createDirectory(at: iconset, withIntermediateDirectories: true)
for base in [16, 32, 128, 256, 512] {
    try render(base).write(to: iconset.appendingPathComponent("icon_\(base)x\(base).png"))
    try render(base * 2).write(to: iconset.appendingPathComponent("icon_\(base)x\(base)@2x.png"))
}
let p = Process()
p.executableURL = URL(fileURLWithPath: "/usr/bin/iconutil")
p.arguments = ["-c", "icns", "-o", out.path, iconset.path]
try p.run()
p.waitUntilExit()
guard p.terminationStatus == 0 else { fatalError("iconutil failed") }
try? FileManager.default.removeItem(at: iconset)
print("wrote \(out.path)")
