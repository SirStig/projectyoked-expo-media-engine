import { requireNativeViewManager } from 'expo-modules-core';
import React, { createElement, forwardRef } from 'react';

let NativeView = null;
try {
    NativeView = requireNativeViewManager('MediaEnginePreview', 'MediaEnginePreviewView');
} catch {
    void 0;
}

function setHostRef(hostRef, value) {
    if (hostRef == null) return;
    if (typeof hostRef === 'function') {
        hostRef(value);
    } else {
        try {
            hostRef.current = value;
        } catch {
            void 0;
        }
    }
}

class MediaEnginePreviewHost extends React.Component {
    _native = null;

    seekTo(seconds) {
        if (this._native?.setNativeProps) {
            this._native.setNativeProps({ currentTime: seconds });
        }
    }

    _attachHandle = () => {
        const r = this.props.hostRef;
        if (!r) return;
        setHostRef(r, { seekTo: (s) => this.seekTo(s) });
    };

    _setNativeRef = (node) => {
        this._native = node;
        this._attachHandle();
    };

    componentDidMount() {
        this._attachHandle();
    }

    componentDidUpdate(prevProps) {
        if (prevProps.hostRef !== this.props.hostRef) {
            setHostRef(prevProps.hostRef, null);
            this._attachHandle();
        }
    }

    componentWillUnmount() {
        setHostRef(this.props.hostRef, null);
    }

    render() {
        if (!NativeView) {
            return null;
        }
        const nativeProps = { ...this.props };
        delete nativeProps.hostRef;
        return createElement(NativeView, { ...nativeProps, ref: this._setNativeRef });
    }
}

export const MediaEnginePreview = forwardRef((props, ref) =>
    createElement(MediaEnginePreviewHost, { ...props, hostRef: ref }),
);
