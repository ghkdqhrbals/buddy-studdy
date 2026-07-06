import Foundation

@MainActor
struct AppActionRunner {
    @discardableResult
    func run<Value>(
        operation: () async throws -> Value,
        onSuccess: (Value) async -> Void = { _ in },
        onFailure: (Error) async -> Void,
        onCompletion: () async -> Void = {}
    ) async -> Value? {
        do {
            let value = try await operation()
            await onSuccess(value)
            await onCompletion()
            return value
        } catch {
            await onFailure(error)
            await onCompletion()
            return nil
        }
    }

    @discardableResult
    func runVoid(
        operation: () async throws -> Void,
        onSuccess: () async -> Void = {},
        onFailure: (Error) async -> Void,
        onCompletion: () async -> Void = {}
    ) async -> Bool {
        let value: Void? = await run(
            operation: operation,
            onSuccess: { _ in
                await onSuccess()
            },
            onFailure: onFailure,
            onCompletion: onCompletion
        )
        return value != nil
    }
}
