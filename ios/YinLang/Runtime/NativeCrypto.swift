import Foundation
import CryptoKit
import Security

enum NativeCrypto {
    static func digest(_ algorithm: String, _ value: String) -> String {
        let data = Data(value.utf8)
        if algorithm.uppercased() == "MD5" { return Insecure.MD5.hash(data: data).map { String(format: "%02x", $0) }.joined() }
        return SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }
    static func aes(_ input: Data, key: Data, iv: Data, mode: String, decrypt: Bool = false) throws -> Data {
        guard [16, 24, 32].contains(key.count), mode == "ECB" || iv.count == 16 else { throw AppFailure.message("AES 密钥或 IV 长度无效") }
        let modes: [String: CCMode] = ["ECB": CCMode(kCCModeECB), "CBC": CCMode(kCCModeCBC), "CTR": CCMode(kCCModeCTR), "CFB": CCMode(kCCModeCFB), "OFB": CCMode(kCCModeOFB)]
        guard let ccMode = modes[mode] else { throw AppFailure.message("不支持的 AES 模式") }
        var cryptor: CCCryptorRef?
        let status = key.withUnsafeBytes { keyBytes in iv.withUnsafeBytes { ivBytes in
            CCCryptorCreateWithMode(CCOperation(decrypt ? kCCDecrypt : kCCEncrypt), ccMode, CCAlgorithm(kCCAlgorithmAES), CCPadding(["ECB", "CBC"].contains(mode) ? ccPKCS7Padding : ccNoPadding), mode == "ECB" ? nil : ivBytes.baseAddress, keyBytes.baseAddress, key.count, nil, 0, 0, CCModeOptions(mode == "CTR" ? kCCModeOptionCTR_BE : 0), &cryptor)
        } }
        guard status == kCCSuccess, let cryptor else { throw AppFailure.message("AES 初始化失败") }
        defer { CCCryptorRelease(cryptor) }
        var result = Data(count: input.count + 32); var written = 0; var final = 0
        let update = result.withUnsafeMutableBytes { output in input.withUnsafeBytes { bytes in CCCryptorUpdate(cryptor, bytes.baseAddress, input.count, output.baseAddress, output.count, &written) } }
        guard update == kCCSuccess else { throw AppFailure.message("AES 处理失败") }
        let finish = result.withUnsafeMutableBytes { output in CCCryptorFinal(cryptor, output.baseAddress!.advanced(by: written), output.count - written, &final) }
        guard finish == kCCSuccess else { throw AppFailure.message("AES 结束处理失败") }; result.count = written + final; return result
    }
    static func rsa(_ data: Data, pem: String) throws -> Data {
        let base64 = pem.components(separatedBy: .newlines).filter { !$0.hasPrefix("---") }.joined()
        guard var encoded = Data(base64Encoded: base64, options: .ignoreUnknownCharacters) else { throw AppFailure.message("RSA 公钥无效") }
        // SecKey expects PKCS#1. Unwrap SubjectPublicKeyInfo when present.
        func length(_ offset: inout Int) throws -> Int {
            guard offset < encoded.count else { throw AppFailure.message("RSA DER 无效") }
            let byte = Int(encoded[offset]); offset += 1
            if byte < 128 { return byte }
            let count = byte & 127; guard count > 0, count <= 4, offset + count <= encoded.count else { throw AppFailure.message("RSA DER 长度无效") }
            var result = 0; for _ in 0..<count { result = result * 256 + Int(encoded[offset]); offset += 1 }; return result
        }
        if pem.contains("BEGIN PUBLIC KEY") {
            var offset = 1; _ = try length(&offset)
            guard offset < encoded.count, encoded[offset] == 0x30 else { throw AppFailure.message("RSA 公钥格式无效") }
            offset += 1; let algorithmLength = try length(&offset); offset += algorithmLength
            guard offset < encoded.count, encoded[offset] == 3 else { throw AppFailure.message("RSA 公钥格式无效") }
            offset += 1; let bitLength = try length(&offset)
            guard bitLength > 1, offset + bitLength <= encoded.count, encoded[offset] == 0 else { throw AppFailure.message("RSA 位串无效") }
            encoded = encoded.subdata(in: (offset + 1)..<(offset + bitLength))
        }
        var error: Unmanaged<CFError>?
        guard let key = SecKeyCreateWithData(encoded as CFData, [kSecAttrKeyType: kSecAttrKeyTypeRSA, kSecAttrKeyClass: kSecAttrKeyClassPublic] as CFDictionary, &error),
              let result = SecKeyCreateEncryptedData(key, .rsaEncryptionOAEPSHA1, data as CFData, &error) else { throw AppFailure.message("RSA 加密失败") }
        return result as Data
    }
}
