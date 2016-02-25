import os

__author__ = 'Davide Caroselli'

__self_dir = os.path.dirname(os.path.realpath(__file__))

MMT_VERSION = '0.11.1'

MMT_ROOT = os.path.abspath(os.path.join(__self_dir, os.pardir))
ENGINES_DIR = os.path.join(MMT_ROOT, 'engines')
RUNTIME_DIR = os.path.join(MMT_ROOT, 'runtime')
OPT_DIR = os.path.join(MMT_ROOT, 'opt')
LIB_DIR = os.path.join(MMT_ROOT, 'lib')
BUILD_DIR = os.path.join(MMT_ROOT, 'build')

BIN_DIR = os.path.join(OPT_DIR, 'bin')

MMT_JAR = os.path.join(BUILD_DIR, 'mmt-' + MMT_VERSION + ".jar")
MMT_LIBS = BUILD_DIR


def mmt_javamain(main_class, args=None):
    command = ['java', '-cp', MMT_JAR, '-Dmmt.home=' + MMT_ROOT, '-Djava.library.path=' + MMT_LIBS, main_class]

    if args is not None:
        command += args

    return command
