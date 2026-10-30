# -*- coding: utf-8 -*-

# Copyright 2026 Mike Fährmann
#
# This program is free software; you can redistribute it and/or modify
# it under the terms of the GNU General Public License version 2 as
# published by the Free Software Foundation.

"""VRF generation utils

adapted from dazedcat19/FMD2
https://github.com/dazedcat19/FMD2/blob/master/lua/modules/MangaFire.lua
"""

from ... import util


def generate(input):
    input = input.encode()

    for table_b64, key_b64, iv in STAGES:
        input = process_stage(
            input,
            util.b64rdecode(table_b64),
            util.b64rdecode(key_b64),
            iv,
        )

    return util.b64encode(
        bytes(input)).replace("+", "-").replace("/", "_")


def process_stage(input, table, key, iv):
    key_len = len(key)

    out = []
    for idx, byte in enumerate(input):
        iv = table[byte ^ key[idx % key_len] ^ iv]
        out.append(iv)
    return out


STAGES = (
    (
        (
            "yINlmUNho8VYJT+ibTIP+9ESiULpVEtMOoD6U6lRE0R/xwXo/Xp9NrUgC4cw/"
            "Lmo33vUyjUE40kUoEWIr/fxfNNcq2s79ShQ5NhNrFnJ4hXPwOu/SuXzIbuTQKG"
            "Fvfm08E9jvCfqAtoDqvQq3dVWPQFmJjgvkISBeXY3BgANR+yVnjGbcxZ47d6k"
            "LNfZPIayTq3/YGySb1KuVZodWp/WGNAO5pfMcpaK53Hhs0allBszaMaxuouOwd"
            "xbwgxIw6YunSsXjI05Yi0j9j4eHKfSXR8Ifo/Od+8iamRfCXTyvm7NGRGYdcQ"
            "0ywcK/u6RXhrbcCm4t2eCtrDgQVecJGkQ+A=="
        ),
        "0Ec58JOY3uBzJK9m3zqIOpdlF7UFiax9DmA=", 0x5A,
    ),
    (
        (
            "IUFltCxD3Oc2cwCgkJffthaOg9cgPUb0LgW6H/VtfcF0kc5F25t+aWj6JH9V"
            "OhOaY0rAFdUxlDnl5BLNvwEJvQtP5qcw7vdb/K+chnbwnspSHT8mz5lqwz41T"
            "ezG0hkO06FTjJZhsyNuFLDpD2ZZxQj/QIRcF90zpmQ7Byu483WsQqUE0C342H"
            "L+JXngRB6fRzxRyVTaKu83h7UYTJ0QMt6ixFh6S3F8gqkKwrGTL3jHNBsD45U"
            "nifK8+RGtishQV2K3rujLKEkiZxpr2dYcudFW4oFsDKhad3CLBvuyTqsCo4B7m"
            "L5IKQ1vXo/MOOvq1I1d8ar9X6Ttu5KF4fZgiA=="
        ),
        "AAdjb1iPY8CiDmq9H34tKTBF8a3oDQ==", 0x35,
    ),
    (
        (
            "NQHlu1/wVO5EmkwQymF810qqY2xG1k2obcas4Z9mCsPEIFl9pRIjFxbJ7ybM"
            "HbBckT5Ton85E0FOeHezbh/mjlEYpmpnlXOS8dgrqeq2KfxImTh1YK9y0PeMN"
            "hzA1OQzSY9brYOJq/l2QnE/hwOeZIhPixVSKIUlDb5vLcH6RWKxkIEMuP0bDw"
            "IqQ71AJJaEaMJL7A6YtyIwoRT+L5v4aZzodN/0+3nOGsfblFjgxSfPzVDjNFe"
            "Nl5P26+kEC/8AHgdrpAbt3hHz3HrRN1Y6e+JHgF7ncFWnoF0y3THL1S71WgWG"
            "Ca6KtSzTCCG58n68nTyj2T3Sshk7utqCtMi/ZQ=="
        ),
        "DELOJgPsVaCcblDtTGMdHzM=", 0xBA,
    ),
)
