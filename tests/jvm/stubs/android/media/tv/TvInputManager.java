package android.media.tv;

import java.util.Collections;
import java.util.List;

public class TvInputManager {
    private final List<TvInputInfo> inputs;

    public TvInputManager() {
        this(Collections.<TvInputInfo>emptyList());
    }

    public TvInputManager(List<TvInputInfo> inputs) {
        this.inputs = inputs;
    }

    public List<TvInputInfo> getTvInputList() {
        return inputs;
    }
}