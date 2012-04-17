=begin
== Copyright and License
Copyright 2008,2009 Complex Automata Simulation Technique (COAST) consortium

GNU Lesser General Public License

This file is part of MUSCLE (Multiscale Coupling Library and Environment).

    MUSCLE is free software: you can redistribute it and/or modify
    it under the terms of the GNU Lesser General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    MUSCLE is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Lesser General Public License for more details.

    You should have received a copy of the GNU Lesser General Public License
    along with MUSCLE.  If not, see <http://www.gnu.org/licenses/>.

== Author
Jan Hegewald
=end

PARENT_DIR = File.dirname(File.expand_path(__FILE__)) unless defined? PARENT_DIR
$LOAD_PATH << PARENT_DIR


class MuscleCli
	#
	def initialize
		require 'optparse'

		# build our cli-args parser
		@env = {}
		@parser = OptionParser.new
	
		@parser.banner += "\nExample: muscle --main plumber --cxa_file path/to/cxa.rb"

		# MUSCLE flags
		@parser.separator "MUSCLE flags:"
		@parser.on("--cxa_file FILE", "file from which to load the cxa") {|arg| @env['cxa_file'] = File.expand_path(arg) }
		@parser.on("--tmp_path ARG", "set root of the tmp path where kernel output will go in dedicated subdirectories") {|arg| @env['tmp_path'] = File.expand_path(arg) }
		@parser.on("--allkernels", "automatically launches all kernels") { @env["allkernels"] = true }
		@parser.on("--mpi", "checks the MPI rank, and runs MUSCLE on rank 0, and calls the kernel 'execute()' on others") { @env['use_mpi'] = true }
		@parser.on("--version", "shows info about this MUSCLE version") do

			puts java("muscle.Version")
			exit true
		end

		# control chief lead head main central
		@parser.separator "JADE flags:"
		@parser.on("--manager HOST:PORT", "IP or hostname:port where the MUSCLE manager can be contacted") {|arg| @env['manager'] = arg; }
		@parser.on("--bindport PORT", "port where this manager should be contacted") {|arg| @env['bindport'] = arg.to_i; }
		@parser.on("--main", "make this instance also a MUSCLE manager") { @env['main'] = true }
		
		@parser.separator "MTO flags:"
		@parser.on("--intercluster", "uses Muscle Transport Overlay") { @env['intercluster'] = true }
		@parser.on("--port_min ARG", "defines lower bound of the port range used (inclusive)") { |arg| @env['port_min'] = arg }
		@parser.on("--port_max ARG", "defines higher bound of the port range used (inclusive)") { |arg| @env['port_max'] = arg }
		@parser.on("--qcg", "enables cooperation with QosCosGrid services (forces local port)") { @env['qcg'] = true }
		@parser.on("--mto HOST:PORT", "IP or hostname where MTO lives") {|arg| @env['mto'] = arg; }
		
		# jvm flags
		@parser.separator "JVM flags:"
		@parser.on("--classpath ARG", "set classpath for the JVM") {|arg| @env["CLASSPATH"] = arg }
		@parser.on("--logging_config_path FILE", "set logging configuration") {|arg| @env['logging_config_path'] = arg }
		@parser.on("--jvmflags ARR", "additional flags to be passed to the jvm (e.g. --jvmflags -da,-help,-Duser.language=en)", Array) {|arr| @env["jvmflags"] = arr; }

		# misc flags
		@parser.separator "misc flags:"
		@parser.on("-h", "--help") { RDoc::usage_no_exit('Synopsis');puts @parser.help; exit }
		@parser.on("--print_env=[KEY0,KEY1,...]", Array, "prints the internal preferences, e.g. --print_env=CLASSPATH") {|val| if val.nil? then @env['print_env'] = true;else @env['print_env'] = val;end }
		@parser.on("-v", "--verbose") { @env['verbose'] = true }
		@parser.on("--quiet") { @env['quiet'] = true }
		@parser.on("-p", "--print", "print command to stdout but do not execute it") { @env['execute'] = false; @env['verbose'] = true }

	end


	# returns remaining args and the cli env
	def parse(args)

		# parse CLI args
	
		@parser.parse!(args) rescue(puts $!;puts @parser.help; exit)
		
		return args, @env
	end
	
	
	#
	def help
		@parser.help
	end
	
	#
	attr_reader :parser
end# class Cli