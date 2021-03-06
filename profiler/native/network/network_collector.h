/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#ifndef NETWORK_NETWORK_COLLECTOR_H_
#define NETWORK_NETWORK_COLLECTOR_H_

#include "network/network_sampler.h"
#include "network/network_files.h"
#include "profiler_server/profiler_data_service.h"

#include <atomic>
#include <memory>
#include <thread>
#include <vector>

namespace profiler {

// Class that runs in the background, continuously collecting network data
class NetworkCollector final {
 public:
  NetworkCollector(int pid, profiler_server::ProfilerDataService *service)
      : pid_(pid), service_(service) {}
  ~NetworkCollector();

  // Creates a thread that collects and saves network data continually.
  void StartProfile();

  // Stops collecting data and wait for thread exit.
  void StopProfile();

 private:
  // First reads app uid from file, then creates app network data samplers;
  // collectors are saved into a vector member variable.
  void CreateSamplers();

  // Continually collects data on a background thread until stopped.
  void Collect();

  static const int kSleepMicroseconds = 300 * 1000;

  // App pid.
  int pid_;
  // Service to pass data to.
  profiler_server::ProfilerDataService *service_;
  // Thread that network profile operations run on.
  std::thread profiler_thread_;
  // True if profile operations is running, false otherwise.
  std::atomic_bool is_running_;
  // Vector to hold data collectors which may need some steps to create.
  std::vector<std::unique_ptr<NetworkSampler>> samplers_;
};

}  // namespace profiler

#endif // NETWORK_NETWORK_COLLECTOR_H_
