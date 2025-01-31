.. _cli_import:

***************************************************************************************************
import
***************************************************************************************************


Synopsis
--------

**comet import**


Description
-----------


| Move the files from the landing area to the pending area.
| files are loaded one domain at a time
| each domain has its own directory and is specified in the "directory" key of Domain YML file
| compressed files are uncompressed if a corresponding ack file exist.
| Compressed files are recognized by their extension which should be one of .tgz, .zip, .gz.
| raw file should also have a corresponding ack file
| before moving the files to the pending area, the ack files are deleted
| To import files without ack specify an empty "ack" key (aka ack:"") in the domain YML file.
| "ack" is the default ack extension searched for but you may specify a different one in the domain YML file.
| 

.. code-block:: console

   comet import


Options
-------

