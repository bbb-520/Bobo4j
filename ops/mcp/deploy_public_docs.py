#!/usr/bin/env python3
"""Opt-in stage-two entry point using the same guarded one-service release path."""
from deploy_phase1 import main

if __name__ == '__main__':
    main(public_docs=True)
