import Foundation
import UIKit
import ImageIO

enum PlaylistCoverImage {
    // Bound decoding before constructing UIImage; normalize HEIC/PNG/photos to
    // a small square JPEG accepted by Navidrome's multipart image endpoint.
    static func prepare(_ data: Data) throws -> Data {
        guard !data.isEmpty, data.count <= 25 * 1_048_576,
              let source = CGImageSourceCreateWithData(data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary),
              let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceShouldCacheImmediately: true,
                kCGImageSourceThumbnailMaxPixelSize: 1600,
              ] as CFDictionary) else { throw AppFailure.message("无法读取图片，请选择不超过 25 MB 的照片或图片文件") }
        let edge = CGFloat(min(1200, min(image.width, image.height)))
        guard edge > 0 else { throw AppFailure.message("图片尺寸无效") }
        let format = UIGraphicsImageRendererFormat(); format.scale = 1; format.opaque = true
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: edge, height: edge), format: format)
        let square = renderer.image { context in
            UIColor.white.setFill(); context.fill(CGRect(x: 0, y: 0, width: edge, height: edge))
            let scale = edge / CGFloat(min(image.width, image.height))
            let width = CGFloat(image.width) * scale, height = CGFloat(image.height) * scale
            UIImage(cgImage: image).draw(in: CGRect(x: (edge - width) / 2, y: (edge - height) / 2, width: width, height: height))
        }
        guard let jpeg = square.jpegData(compressionQuality: 0.86), jpeg.count <= 4 * 1_048_576 else { throw AppFailure.message("封面转换失败，请更换图片") }
        return jpeg
    }
}
