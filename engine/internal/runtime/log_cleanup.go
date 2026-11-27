package runtime

func (e *Engine) AcknowledgeLogs(keys []string) (map[string]any, error) {
	count, err := e.logger.AcknowledgeLogs(keys)
	if err != nil {
		return nil, err
	}
	return map[string]any{"ok": true, "removed": count}, nil
}
